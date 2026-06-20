package com.katixo.ai.commons.sidecar;

import java.time.Duration;

/**
 * Cross-cutting settings for a {@link SidecarClient}: its logical name, connect/read timeouts, and a
 * bounded retry/backoff policy. The actual HTTP timeouts are applied by the subclass's own HTTP
 * client (RestClient, JDK HttpClient, ...); these values are the shared source of truth it reads.
 *
 * @param name           logical sidecar name for logs/errors (e.g. {@code "ollama"}, {@code "comfyui"})
 * @param connectTimeout connection timeout the subclass should apply
 * @param readTimeout    read/response timeout the subclass should apply
 * @param maxRetries     number of retries AFTER the first attempt (0 = no retry, exactly one attempt)
 * @param backoffBase    base backoff; attempt {@code i} waits {@code backoffBase * 2^i}
 */
public record SidecarConfig(String name, Duration connectTimeout, Duration readTimeout,
                            int maxRetries, Duration backoffBase) {

    public SidecarConfig {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("sidecar name is required");
        }
        if (connectTimeout == null || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("connectTimeout must be >= 0");
        }
        if (readTimeout == null || readTimeout.isNegative()) {
            throw new IllegalArgumentException("readTimeout must be >= 0");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0");
        }
        if (backoffBase == null || backoffBase.isNegative()) {
            throw new IllegalArgumentException("backoffBase must be >= 0");
        }
    }

    /** No retries (single attempt); preserves the behavior of clients that never retried before. */
    public static SidecarConfig noRetry(String name, Duration connectTimeout, Duration readTimeout) {
        return new SidecarConfig(name, connectTimeout, readTimeout, 0, Duration.ofMillis(200));
    }

    /** A small bounded retry budget for transient connectivity blips. */
    public static SidecarConfig withRetries(String name, Duration connectTimeout, Duration readTimeout,
                                            int maxRetries) {
        return new SidecarConfig(name, connectTimeout, readTimeout, maxRetries, Duration.ofMillis(200));
    }
}
