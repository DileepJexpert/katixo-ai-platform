package com.katixo.ai.commons.sidecar;

/**
 * Reachability snapshot of a sidecar, returned by {@link SidecarClient#probe()}.
 *
 * @param reachable whether the sidecar answered a health/ping request
 * @param name      the sidecar's logical name
 * @param detail    optional extra detail (e.g. the error when unreachable); may be {@code null}
 */
public record SidecarHealth(boolean reachable, String name, String detail) {

    public static SidecarHealth up(String name) {
        return new SidecarHealth(true, name, null);
    }

    public static SidecarHealth down(String name, String detail) {
        return new SidecarHealth(false, name, detail);
    }
}
