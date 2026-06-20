package com.katixo.ai.commons.gpu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Cross-process {@link GpuResourceGuard} using a PostgreSQL <em>session-level</em> advisory lock.
 *
 * <p>Acquisition polls {@code pg_try_advisory_lock(key)} on a dedicated connection until it succeeds
 * or the acquire-timeout elapses (it never blocks forever). The lock is released with
 * {@code pg_advisory_unlock(key)} on the <em>same</em> connection in a finally block; the connection
 * is then closed, which also frees the session lock as a backstop. A max-hold watchdog closes the
 * connection if a job overruns, so a wedged GPU call cannot hold the lock indefinitely.
 *
 * <p>Session-level (not {@code pg_advisory_xact_lock}) is deliberate: a GPU job may run for minutes,
 * and we must not hold an open database transaction for that long. We hold a connection + a session
 * lock instead, with autocommit on.
 *
 * <p><b>Critical caveat.</b> PostgreSQL advisory locks are scoped to a database — the lock tag
 * includes the database OID. Every process MUST point this guard's {@link DataSource} at the SAME
 * database (the shared "lock authority"), not merely the same server, or no serialization happens.
 * See MIGRATION.md.
 */
public final class PostgresAdvisoryGpuGuard implements GpuResourceGuard {

    private static final Logger log = LoggerFactory.getLogger(PostgresAdvisoryGpuGuard.class);

    private final DataSource lockAuthority;
    private final GpuGuardConfig config;
    private final ScheduledExecutorService watchdog =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "gpu-guard-watchdog");
                t.setDaemon(true);
                return t;
            });

    private volatile String currentLabel;
    private volatile boolean held;

    public PostgresAdvisoryGpuGuard(DataSource lockAuthority, GpuGuardConfig config) {
        this.lockAuthority = lockAuthority;
        this.config = config;
    }

    @Override
    public <T> T runExclusively(String jobLabel, GpuTask<T> task) throws Exception {
        final Connection conn = openConnection();
        boolean acquired = false;
        long startWait = System.nanoTime();
        try {
            acquired = acquireWithTimeout(conn);
            if (!acquired) {
                throw new GpuBusyException(jobLabel, config.acquireTimeout());
            }
            held = true;
            currentLabel = jobLabel;
            log.debug("GPU acquired (pg advisory) label='{}' waitedMs={}",
                    jobLabel, Duration.ofNanos(System.nanoTime() - startWait).toMillis());

            // Max-hold safety: if the task overruns, close the lock connection. Postgres releases
            // session advisory locks when the session ends, so the lock can't be stuck forever.
            ScheduledFuture<?> wd = watchdog.schedule(() -> {
                log.warn("GPU job '{}' exceeded max-hold {} — forcibly releasing the lock by closing its connection",
                        jobLabel, config.maxHold());
                closeQuietly(conn);
            }, config.maxHold().toMillis(), TimeUnit.MILLISECONDS);

            long startHold = System.nanoTime();
            try {
                return task.call();
            } finally {
                wd.cancel(false);
                log.debug("GPU released (pg advisory) label='{}' heldMs={}",
                        jobLabel, Duration.ofNanos(System.nanoTime() - startHold).toMillis());
            }
        } finally {
            currentLabel = null;
            held = false;
            if (acquired) {
                unlockQuietly(conn);
            }
            closeQuietly(conn);
        }
    }

    private Connection openConnection() {
        try {
            Connection c = lockAuthority.getConnection();
            c.setAutoCommit(true);
            return c;
        } catch (SQLException e) {
            throw new GpuGuardException("Could not open a connection to the GPU lock authority", e);
        }
    }

    /** Polls pg_try_advisory_lock until acquired or the acquire-timeout elapses. */
    private boolean acquireWithTimeout(Connection conn) {
        long deadline = System.nanoTime() + config.acquireTimeout().toNanos();
        while (true) {
            if (tryAdvisoryLock(conn)) {
                return true;
            }
            if (System.nanoTime() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(config.pollInterval().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private boolean tryAdvisoryLock(Connection conn) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            ps.setLong(1, config.lockKey());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new GpuGuardException("Failed to query pg_try_advisory_lock", e);
        }
    }

    private void unlockQuietly(Connection conn) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            ps.setLong(1, config.lockKey());
            ps.execute();
        } catch (SQLException e) {
            // Closing the connection below ends the session and frees the lock anyway.
            log.debug("pg_advisory_unlock failed (relying on session close): {}", e.getMessage());
        }
    }

    private static void closeQuietly(Connection conn) {
        try {
            if (conn != null && !conn.isClosed()) {
                conn.close();
            }
        } catch (SQLException e) {
            // best effort
        }
    }

    @Override
    public GpuGuardStatus status() {
        return new GpuGuardStatus(held, currentLabel);
    }
}
