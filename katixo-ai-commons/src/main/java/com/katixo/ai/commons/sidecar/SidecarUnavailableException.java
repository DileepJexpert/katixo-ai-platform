package com.katixo.ai.commons.sidecar;

/**
 * Thrown when a localhost media/inference sidecar is unreachable or keeps failing after the
 * configured retries. Carries the sidecar's logical name so callers/handlers can report which
 * dependency is down. Apps may subclass this to keep their own typed exception (e.g. katixo-docai's
 * {@code UpstreamUnavailableException}) while still sharing the base type.
 */
public class SidecarUnavailableException extends RuntimeException {

    private final String service;

    public SidecarUnavailableException(String service, String message, Throwable cause) {
        super(message, cause);
        this.service = service;
    }

    public SidecarUnavailableException(String service, String message) {
        this(service, message, null);
    }

    public String getService() {
        return service;
    }
}
