package com.katixo.ai.commons.gpu;

import java.time.Duration;

/**
 * Immutable configuration for a {@link GpuResourceGuard}.
 *
 * @param lockKey        the fixed advisory-lock key. EVERY Katixo GPU process must use the same key
 *                       AND the same lock-authority database, or nothing is serialized.
 * @param acquireTimeout how long to wait for the lock before failing fast with {@link GpuBusyException}
 * @param pollInterval   how often the Postgres guard re-tries {@code pg_try_advisory_lock} while waiting
 * @param maxHold        safety ceiling on how long one job may hold the lock; past this the Postgres
 *                       guard force-releases (by closing the lock connection)
 * @param mode           which implementation a wiring layer should build
 */
public record GpuGuardConfig(
        long lockKey,
        Duration acquireTimeout,
        Duration pollInterval,
        Duration maxHold,
        Mode mode) {

    /**
     * The well-known shared lock key: ASCII "KATIXOGP" as a positive 64-bit integer. Hard-coded as
     * a default so both apps inherit the same value; override only if you change it in both apps.
     */
    public static final long DEFAULT_LOCK_KEY = 0x4B41_5449_584F_4750L;

    public enum Mode {
        /** Cross-process serialization via a Postgres advisory lock (production). */
        POSTGRES,
        /** Single-JVM serialization via a semaphore (tests / single-process). */
        IN_PROCESS
    }

    public GpuGuardConfig {
        if (acquireTimeout == null || acquireTimeout.isNegative()) {
            throw new IllegalArgumentException("acquireTimeout must be >= 0");
        }
        if (pollInterval == null || pollInterval.isZero() || pollInterval.isNegative()) {
            throw new IllegalArgumentException("pollInterval must be > 0");
        }
        if (maxHold == null || maxHold.isZero() || maxHold.isNegative()) {
            throw new IllegalArgumentException("maxHold must be > 0");
        }
        if (mode == null) {
            throw new IllegalArgumentException("mode is required");
        }
    }

    /** Sensible defaults: 60s acquire, 250ms poll, 15m max-hold, the shared default key. */
    public static GpuGuardConfig defaults(Mode mode) {
        return new GpuGuardConfig(
                DEFAULT_LOCK_KEY,
                Duration.ofSeconds(60),
                Duration.ofMillis(250),
                Duration.ofMinutes(15),
                mode);
    }

    /** Copy with a different acquire-timeout (handy for callers that want to wait longer/shorter). */
    public GpuGuardConfig withAcquireTimeout(Duration newAcquireTimeout) {
        return new GpuGuardConfig(lockKey, newAcquireTimeout, pollInterval, maxHold, mode);
    }
}
