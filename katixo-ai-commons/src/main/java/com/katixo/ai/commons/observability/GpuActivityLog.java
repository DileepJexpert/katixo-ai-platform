package com.katixo.ai.commons.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tiny helper for consistent, greppable GPU-contention logging across both apps. Logs acquire/run/
 * release events under one logger name ({@code katixo.ai.gpu.activity}) and one format, so GPU usage
 * by image-generator and katixo-docai can be followed in a single stream.
 *
 * <p>Optional: the guards already debug-log; use this when an app wants an explicit, INFO-level
 * audit line per GPU job (who waited, how long, who held, how long).
 */
public final class GpuActivityLog {

    private static final Logger log = LoggerFactory.getLogger("katixo.ai.gpu.activity");

    private final String app;

    public GpuActivityLog(String app) {
        this.app = app;
    }

    public void acquired(String label, long waitedMs) {
        log.info("[gpu] app={} label='{}' event=acquired waitedMs={}", app, label, waitedMs);
    }

    public void released(String label, long heldMs) {
        log.info("[gpu] app={} label='{}' event=released heldMs={}", app, label, heldMs);
    }

    public void busy(String label, long waitedMs) {
        log.warn("[gpu] app={} label='{}' event=busy waitedMs={} (GPU held by another job)", app, label, waitedMs);
    }
}
