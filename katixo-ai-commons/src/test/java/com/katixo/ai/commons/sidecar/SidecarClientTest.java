package com.katixo.ai.commons.sidecar;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Covers the shared SidecarClient plumbing: URL building, idempotency keys, retry/backoff. */
class SidecarClientTest {

    /** A minimal concrete client that exposes the protected helpers for testing. */
    private static final class StubClient extends SidecarClient {
        StubClient(int maxRetries) {
            super("http://localhost:9/",
                    new SidecarConfig("stub", Duration.ofSeconds(1), Duration.ofSeconds(1),
                            maxRetries, Duration.ofMillis(1)));
        }
        @Override public SidecarHealth probe() { return SidecarHealth.up("stub"); }
        String urlFor(String p) { return url(p); }
        String key() { return newIdempotencyKey(); }
        <T> T retry(SidecarCall<T> c, Predicate<Exception> r) { return withRetry("op", c, r); }
    }

    @Test
    void trimsTrailingSlashAndBuildsUrls() {
        assertThat(new StubClient(0).urlFor("/ocr")).isEqualTo("http://localhost:9/ocr");
    }

    @Test
    void idempotencyKeysAreUnique() {
        StubClient c = new StubClient(0);
        assertThat(c.key()).isNotEqualTo(c.key());
    }

    @Test
    void retriesThenSucceeds() {
        StubClient c = new StubClient(3);
        AtomicInteger n = new AtomicInteger();
        String r = c.retry(() -> {
            if (n.incrementAndGet() < 3) throw new IOException("transient");
            return "ok";
        }, e -> true);
        assertThat(r).isEqualTo("ok");
        assertThat(n.get()).isEqualTo(3);
    }

    @Test
    void wrapsExhaustionInSidecarUnavailable() {
        StubClient c = new StubClient(1);
        assertThatThrownBy(() -> c.retry(() -> { throw new IOException("down"); }, e -> true))
                .isInstanceOf(SidecarUnavailableException.class)
                .hasMessageContaining("stub");
    }

    @Test
    void doesNotRetryWhenPredicateRejects() {
        StubClient c = new StubClient(5);
        AtomicInteger n = new AtomicInteger();
        assertThatThrownBy(() -> c.retry(() -> { n.incrementAndGet(); throw new IllegalArgumentException("4xx"); },
                e -> false))
                .isInstanceOf(SidecarUnavailableException.class);
        assertThat(n.get()).as("a non-retryable failure must not be retried").isEqualTo(1);
    }
}
