package com.katixo.ai.commons.dto;

import java.util.Map;

/**
 * Engine-neutral description of a unit of GPU/inference work — generic enough for both image
 * generation and document extraction. This is shared <em>vocabulary</em>: it is the contract a
 * future broker/scheduler service would speak, and a convenient carrier for app-level job params.
 * It does not replace either app's own request types.
 *
 * @param type           kind of work (e.g. {@code "image"}, {@code "image_to_video"}, {@code "extract"})
 * @param label          short human label for logs/diagnostics
 * @param params         engine-specific parameters (opaque to commons)
 * @param idempotencyKey caller-supplied key so a retried submit is not executed twice; may be {@code null}
 */
public record GpuJobRequest(String type, String label, Map<String, Object> params, String idempotencyKey) {

    public GpuJobRequest {
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    public static GpuJobRequest of(String type, String label) {
        return new GpuJobRequest(type, label, Map.of(), null);
    }
}
