package com.katixo.ai.commons.gpu;

import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Proves the REAL cross-process guarantee against a real PostgreSQL: two independent
 * {@link DataSource}s (= two independent connection pools = two "apps", an image-gen render and a
 * docai extraction) are serialized by the shared advisory lock, and a second app fails fast when the
 * first holds the GPU past the acquire-timeout.
 *
 * <p>Self-skips when Docker is unavailable, so {@code mvn install} stays green in Docker-less
 * environments — {@link InProcessGpuGuardTest} still proves the contract there.
 */
class PostgresAdvisoryGpuGuardTest {

    private static final long KEY = GpuGuardConfig.DEFAULT_LOCK_KEY;

    @Test
    void twoSeparateProcessesAreSerializedByTheAdvisoryLock() throws Exception {
        assumeTrue(dockerAvailable(), "Docker not available; skipping real-Postgres advisory-lock test");

        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            DataSource appA = dataSource(pg);   // "image-generator"
            DataSource appB = dataSource(pg);   // "katixo-docai"
            GpuGuardConfig cfg = new GpuGuardConfig(KEY, Duration.ofSeconds(10),
                    Duration.ofMillis(50), Duration.ofMinutes(1), GpuGuardConfig.Mode.POSTGRES);
            GpuResourceGuard guardA = new PostgresAdvisoryGpuGuard(appA, cfg);
            GpuResourceGuard guardB = new PostgresAdvisoryGpuGuard(appB, cfg);

            AtomicInteger concurrent = new AtomicInteger();
            AtomicInteger maxConcurrent = new AtomicInteger();
            CountDownLatch start = new CountDownLatch(1);

            GpuResourceGuard.GpuTask<Void> work = () -> {
                int now = concurrent.incrementAndGet();
                maxConcurrent.accumulateAndGet(now, Math::max);
                Thread.sleep(300);                  // hold the "GPU"
                concurrent.decrementAndGet();
                return null;
            };

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<?> fa = pool.submit(() -> { start.await(); return guardA.runExclusively("image-gen", work); });
                Future<?> fb = pool.submit(() -> { start.await(); return guardB.runExclusively("docai", work); });
                start.countDown();
                fa.get(30, TimeUnit.SECONDS);
                fb.get(30, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }

            assertThat(maxConcurrent.get())
                    .as("the two processes must never hold the GPU at the same time")
                    .isEqualTo(1);
        }
    }

    @Test
    void secondProcessFailsFastWhenFirstHoldsBeyondAcquireTimeout() throws Exception {
        assumeTrue(dockerAvailable(), "Docker not available; skipping real-Postgres advisory-lock test");

        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            GpuGuardConfig cfg = new GpuGuardConfig(KEY, Duration.ofMillis(300),
                    Duration.ofMillis(50), Duration.ofMinutes(1), GpuGuardConfig.Mode.POSTGRES);
            GpuResourceGuard guardA = new PostgresAdvisoryGpuGuard(dataSource(pg), cfg);
            GpuResourceGuard guardB = new PostgresAdvisoryGpuGuard(dataSource(pg), cfg);

            CountDownLatch holding = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Thread holder = new Thread(() -> {
                try {
                    guardA.runExclusively("holder", () -> { holding.countDown(); release.await(); return null; });
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, "pg-holder");
            holder.start();
            assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> guardB.runExclusively("waiter", () -> null))
                    .isInstanceOf(GpuBusyException.class);

            release.countDown();
            holder.join(5_000);
        }
    }

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    private static DataSource dataSource(PostgreSQLContainer<?> pg) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(pg.getJdbcUrl());
        ds.setUser(pg.getUsername());
        ds.setPassword(pg.getPassword());
        return ds;
    }
}
