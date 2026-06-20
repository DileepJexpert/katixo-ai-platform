package com.katixo.ai.commons.sidecar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.function.Predicate;

/**
 * Base for clients that call a single localhost media/inference sidecar over HTTP. It owns the
 * cross-cutting concerns shared by every such client — base-URL normalisation, a per-call
 * idempotency key, a bounded retry/backoff helper, and a {@link #probe()} health contract — but is
 * deliberately <b>HTTP-stack-agnostic</b>: subclasses perform the actual exchange with whatever
 * client they already use (Spring {@code RestClient}, JDK {@code HttpClient}, ...).
 *
 * <p>No engine-specific request building lives here. ComfyUI's graph templating, Ollama's JSON-mode
 * body, the OCR multipart, etc. stay in their app — the shared surface is the abstraction, not the
 * implementation.
 */
public abstract class SidecarClient {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    /** Base URL with any trailing slashes trimmed. */
    protected final String baseUrl;
    protected final SidecarConfig config;

    protected SidecarClient(String baseUrl, SidecarConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("SidecarConfig is required");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl is required for sidecar " + config.name());
        }
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.config = config;
    }

    /** Absolute URL for a sidecar path. {@code path} should start with '/'. */
    protected String url(String path) {
        return baseUrl + path;
    }

    /** A fresh idempotency key; attach as a header or engine client id where the sidecar supports it. */
    protected String newIdempotencyKey() {
        return UUID.randomUUID().toString();
    }

    /**
     * Runs {@code call} with bounded retries and exponential backoff. {@code retryable} decides which
     * failures are worth retrying (e.g. connection refused) versus fatal (e.g. an HTTP 4xx). On
     * exhaustion the last error is wrapped in {@link SidecarUnavailableException}; subclasses that
     * keep their own typed exception can catch and re-wrap.
     */
    protected <T> T withRetry(String op, SidecarCall<T> call, Predicate<Exception> retryable) {
        int attempts = config.maxRetries() + 1;
        Exception last = null;
        for (int i = 0; i < attempts; i++) {
            try {
                return call.run();
            } catch (Exception e) {
                last = e;
                boolean lastAttempt = (i == attempts - 1);
                if (lastAttempt || !retryable.test(e)) {
                    break;
                }
                long backoffMs = config.backoffBase().toMillis() * (1L << i);
                log.debug("{} op '{}' attempt {}/{} failed, retrying in {}ms: {}",
                        config.name(), op, i + 1, attempts, backoffMs, e.toString());
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new SidecarUnavailableException(config.name(),
                config.name() + " sidecar call '" + op + "' failed", last);
    }

    /** Convenience: retry on any exception. */
    protected <T> T withRetry(String op, SidecarCall<T> call) {
        return withRetry(op, call, e -> true);
    }

    /** @return a reachability snapshot for this sidecar; never throws. */
    public abstract SidecarHealth probe();

    /** A single HTTP exchange the subclass performs with its own client. */
    @FunctionalInterface
    protected interface SidecarCall<T> {
        T run() throws Exception;
    }
}
