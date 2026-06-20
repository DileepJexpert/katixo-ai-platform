package com.katixo.ai.commons.gpu;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the guard contract with a fast, Docker-free, single-JVM guard: two GPU jobs fired at once
 * never overlap, a caller fails fast when the GPU is held past its acquire-timeout, and the lock is
 * always released (even when the task throws). This is the always-on proof of the acceptance
 * criterion; {@link PostgresAdvisoryGpuGuardTest} proves the same across real, separate processes.
 */
class InProcessGpuGuardTest {

    // Unique key per guard so the static permit registry can't leak between test methods.
    private static final AtomicLong KEY = new AtomicLong(1000);

    private GpuResourceGuard guard(Duration acquireTimeout) {
        GpuGuardConfig cfg = new GpuGuardConfig(
                KEY.incrementAndGet(), acquireTimeout, Duration.ofMillis(20),
                Duration.ofMinutes(1), GpuGuardConfig.Mode.IN_PROCESS);
        return new InProcessGpuGuard(cfg);
    }

    @Test
    void twoConcurrentGpuJobsAreSerialized() throws Exception {
        GpuResourceGuard guard = guard(Duration.ofSeconds(5));
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);

        Runnable job = () -> {
            try {
                start.await();
                guard.runExclusively("job", () -> {
                    int now = concurrent.incrementAndGet();
                    maxConcurrent.accumulateAndGet(now, Math::max);
                    Thread.sleep(200);              // hold the "GPU"
                    concurrent.decrementAndGet();
                    completed.incrementAndGet();
                    return null;
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };

        // Simulate an image-gen render and a docai extraction firing simultaneously.
        Thread imageGen = new Thread(job, "image-gen-render");
        Thread docai = new Thread(job, "docai-extract");
        imageGen.start();
        docai.start();
        start.countDown();
        imageGen.join(5_000);
        docai.join(5_000);

        assertThat(completed.get()).isEqualTo(2);
        assertThat(maxConcurrent.get()).as("the two GPU jobs must never run at the same time").isEqualTo(1);
    }

    @Test
    void secondCallerFailsFastWhenGpuHeldBeyondAcquireTimeout() throws Exception {
        GpuResourceGuard guard = guard(Duration.ofMillis(80));
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Thread holder = new Thread(() -> {
            try {
                guard.runExclusively("holder", () -> {
                    holding.countDown();
                    release.await();
                    return null;
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "holder");
        holder.start();
        assertThat(holding.await(2, TimeUnit.SECONDS)).isTrue();

        long t0 = System.nanoTime();
        assertThatThrownBy(() -> guard.runExclusively("waiter", () -> "never"))
                .isInstanceOf(GpuBusyException.class);
        long waitedMs = (System.nanoTime() - t0) / 1_000_000;
        assertThat(waitedMs).as("must fail fast, not block forever").isLessThan(2_000);

        release.countDown();
        holder.join(2_000);
    }

    @Test
    void releasesLockEvenWhenTaskThrows() throws Exception {
        GpuResourceGuard guard = guard(Duration.ofSeconds(1));

        assertThatThrownBy(() ->
                guard.runExclusively("boom", () -> { throw new IllegalStateException("kaboom"); }))
                .isInstanceOf(IllegalStateException.class);

        // The lock must be free again immediately.
        String result = guard.runExclusively("after", () -> "ok");
        assertThat(result).isEqualTo("ok");
        assertThat(guard.status().held()).isFalse();
    }
}
