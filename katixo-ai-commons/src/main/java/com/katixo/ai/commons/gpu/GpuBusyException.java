package com.katixo.ai.commons.gpu;

import java.time.Duration;

/**
 * Thrown when the GPU guard could not be acquired within the configured acquire-timeout — i.e.
 * another job (possibly in the other application) is using the GPU. The guard never blocks
 * indefinitely; the caller decides whether to queue, retry later, or surface a "GPU busy" response.
 */
public class GpuBusyException extends RuntimeException {

    private final String jobLabel;
    private final Duration waited;

    public GpuBusyException(String jobLabel, Duration waited) {
        super("GPU is busy: could not acquire the lock for '" + jobLabel + "' within " + waited);
        this.jobLabel = jobLabel;
        this.waited = waited;
    }

    public String getJobLabel() {
        return jobLabel;
    }

    public Duration getWaited() {
        return waited;
    }
}
