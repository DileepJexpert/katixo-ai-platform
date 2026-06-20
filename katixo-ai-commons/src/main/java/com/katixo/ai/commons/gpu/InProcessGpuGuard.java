package com.katixo.ai.commons.gpu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Single-JVM {@link GpuResourceGuard} backed by a fair {@link Semaphore}. Serializes GPU work
 * within ONE process only — it does <b>not</b> coordinate across processes, so it cannot stop
 * image-generator and katixo-docai colliding when they run as separate apps. Use it for tests,
 * single-process runs, and as a fallback when no Postgres lock authority is configured. Production
 * cross-process serialization requires {@link PostgresAdvisoryGpuGuard}.
 *
 * <p>Permits are keyed by lock key in a static registry, so multiple guard instances in the same
 * JVM that share a key also share the single permit.
 */
public final class InProcessGpuGuard implements GpuResourceGuard {

    private static final Logger log = LoggerFactory.getLogger(InProcessGpuGuard.class);

    private static final ConcurrentHashMap<Long, Semaphore> PERMITS = new ConcurrentHashMap<>();

    private final GpuGuardConfig config;
    private final Semaphore permit;
    private volatile String currentLabel;

    public InProcessGpuGuard(GpuGuardConfig config) {
        this.config = config;
        this.permit = PERMITS.computeIfAbsent(config.lockKey(), k -> new Semaphore(1, true));
    }

    @Override
    public <T> T runExclusively(String jobLabel, GpuTask<T> task) throws Exception {
        long startWait = System.nanoTime();
        boolean acquired;
        try {
            acquired = permit.tryAcquire(config.acquireTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GpuBusyException(jobLabel, config.acquireTimeout());
        }
        if (!acquired) {
            throw new GpuBusyException(jobLabel, config.acquireTimeout());
        }

        Duration waited = Duration.ofNanos(System.nanoTime() - startWait);
        currentLabel = jobLabel;
        long startHold = System.nanoTime();
        log.debug("GPU acquired (in-process) label='{}' waitedMs={}", jobLabel, waited.toMillis());
        try {
            return task.call();
        } finally {
            currentLabel = null;
            permit.release();
            log.debug("GPU released (in-process) label='{}' heldMs={}",
                    jobLabel, Duration.ofNanos(System.nanoTime() - startHold).toMillis());
        }
    }

    @Override
    public GpuGuardStatus status() {
        return new GpuGuardStatus(permit.availablePermits() == 0, currentLabel);
    }
}
