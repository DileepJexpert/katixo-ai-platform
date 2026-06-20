package com.katixo.ai.commons.gpu;

/**
 * Thrown when the GPU lock infrastructure itself fails — for example the lock-authority database is
 * unreachable, or a {@code pg_advisory_lock} query errors. Distinct from {@link GpuBusyException},
 * which is the normal "someone else has the GPU" signal.
 */
public class GpuGuardException extends RuntimeException {

    public GpuGuardException(String message, Throwable cause) {
        super(message, cause);
    }

    public GpuGuardException(String message) {
        super(message);
    }
}
