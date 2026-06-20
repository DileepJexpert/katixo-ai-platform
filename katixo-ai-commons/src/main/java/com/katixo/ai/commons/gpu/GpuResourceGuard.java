package com.katixo.ai.commons.gpu;

/**
 * Serializes access to the single shared GPU. At most one job may hold the guard at a time;
 * callers wrap their GPU work in {@link #runExclusively}. The guard acquires before running and
 * <em>always</em> releases afterwards, even on exception.
 *
 * <p>There are two implementations:
 * <ul>
 *   <li>{@link PostgresAdvisoryGpuGuard} — the production guard. It serializes work across
 *       <em>processes</em> (e.g. image-generator and katixo-docai) via a PostgreSQL advisory lock,
 *       and is the only thing that prevents the two apps hitting one GPU at once.</li>
 *   <li>{@link InProcessGpuGuard} — a single-JVM guard for tests / single-process use. It does NOT
 *       coordinate across processes.</li>
 * </ul>
 *
 * <p>Contract: if the lock cannot be acquired within the configured acquire-timeout the guard
 * throws {@link GpuBusyException} (it never blocks forever); the caller decides whether to queue,
 * retry, or surface a busy response. Do <b>not</b> nest {@code runExclusively} calls — a second
 * acquisition on a distinct connection would self-deadlock.
 */
public interface GpuResourceGuard {

    /**
     * Acquire the GPU lock, run {@code task}, and release. The task may return a value and may
     * throw; its exception propagates unchanged after the lock is released.
     *
     * @param jobLabel short human label for logs/diagnostics (e.g. {@code "image"}, {@code "docai-llm-TEXT"})
     * @param task     the GPU work to run under exclusive access
     * @throws GpuBusyException   if the lock can't be acquired within the acquire-timeout
     * @throws GpuGuardException  if the lock infrastructure itself fails
     * @throws Exception          whatever {@code task} throws
     */
    <T> T runExclusively(String jobLabel, GpuTask<T> task) throws Exception;

    /** Best-effort snapshot of whether the guard is currently held. Never throws. */
    GpuGuardStatus status();

    /** GPU work that returns a value and may throw a checked exception. */
    @FunctionalInterface
    interface GpuTask<T> {
        T call() throws Exception;
    }
}
