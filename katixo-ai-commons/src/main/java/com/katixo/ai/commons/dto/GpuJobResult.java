package com.katixo.ai.commons.dto;

/**
 * Engine-neutral outcome of a {@link GpuJobRequest}. Shared vocabulary, like the request type.
 *
 * @param status    terminal (or current) lifecycle status
 * @param resultRef opaque reference to the produced artifact (e.g. an asset id), or {@code null}
 * @param error     failure message when {@link JobStatus#FAILED}, else {@code null}
 * @param latencyMs wall-clock latency of the work in milliseconds
 */
public record GpuJobResult(JobStatus status, String resultRef, String error, long latencyMs) {

    public static GpuJobResult done(String resultRef, long latencyMs) {
        return new GpuJobResult(JobStatus.DONE, resultRef, null, latencyMs);
    }

    public static GpuJobResult failed(String error, long latencyMs) {
        return new GpuJobResult(JobStatus.FAILED, null, error, latencyMs);
    }
}
