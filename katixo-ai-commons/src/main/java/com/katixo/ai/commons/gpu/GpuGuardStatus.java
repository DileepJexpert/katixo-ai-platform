package com.katixo.ai.commons.gpu;

/**
 * Best-effort snapshot of a {@link GpuResourceGuard}.
 *
 * @param held         whether the guard is currently held by some job
 * @param currentLabel the label of the holding job, or {@code null} if idle
 */
public record GpuGuardStatus(boolean held, String currentLabel) {

    public static GpuGuardStatus idle() {
        return new GpuGuardStatus(false, null);
    }
}
