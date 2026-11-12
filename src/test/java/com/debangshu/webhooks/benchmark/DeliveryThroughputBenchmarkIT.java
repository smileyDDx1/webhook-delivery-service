package com.debangshu.webhooks.benchmark;

import com.debangshu.webhooks.IntegrationTest;
import com.debangshu.webhooks.config.AppProperties;
import com.debangshu.webhooks.config.AppProperties.ExecutorType;
import com.debangshu.webhooks.delivery.DeliveryProcessor;
import com.debangshu.webhooks.delivery.DeliveryQueue;
import com.debangshu.webhooks.delivery.DeliveryWorker;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How fast can one instance deliver to a slow receiver (every request takes 100 ms)?
 * Runs the real worker + real database, only the receiver is a tiny in-process HTTP server.
 *
 * Run with: ./mvnw verify -Pbenchmark
 */
@Tag("benchmark")
class DeliveryThroughputBenchmarkIT extends IntegrationTest {

    private static final int RECEIVER_LATENCY_MS = 100;
    private static HttpServer slowReceiver;
    private static final AtomicInteger received = new AtomicInteger();

    @Autowired
    DeliveryQueue queue;

    @Autowired
    DeliveryProcessor processor;

    @Autowired
    AppProperties properties;

    record Setup(ExecutorType type, int maxConcurrency, int deliveries) {
    }

    @BeforeAll
    static void startSlowReceiver() throws IOException {
        slowReceiver = HttpServer.create(new InetSocketAddress("localhost", 0), 1000);
        slowReceiver.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        slowReceiver.createContext("/slow", exchange -> {
            exchange.getRequestBody().readAllBytes();
            try {
                Thread.sleep(RECEIVER_LATENCY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            received.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        slowReceiver.start();
    }

    @AfterAll
    static void stopSlowReceiver() {
        slowReceiver.stop(0);
    }

    @Test
    void throughputWithDifferentConcurrency() {
        worker.pause(); // the benchmark starts its own workers with the settings below
        List<Setup> setups = List.of(
                new Setup(ExecutorType.PLATFORM, 1, 100),
                new Setup(ExecutorType.PLATFORM, 10, 1_000),
                new Setup(ExecutorType.PLATFORM, 50, 2_000),
                new Setup(ExecutorType.PLATFORM, 200, 5_000),
                new Setup(ExecutorType.VIRTUAL, 200, 5_000));

        StringBuilder table = new StringBuilder("""

                ### Delivery throughput (receiver answers after %d ms)

                | Threads | Max in flight | Deliveries | Time (s) | Deliveries/s | Peak JVM threads |
                |---|---|---|---|---|---|
                """.formatted(RECEIVER_LATENCY_MS));

        for (Setup setup : setups) {
            resetDatabase();
            long endpointId = createBenchmarkEndpoint();
            insertDueDeliveries(endpointId, setup.deliveries());
            received.set(0);

            DeliveryWorker benchmarkWorker = new DeliveryWorker(queue, processor, settings(setup));
            ThreadMXBean threads = ManagementFactory.getThreadMXBean();
            threads.resetPeakThreadCount();
            long start = System.nanoTime();
            benchmarkWorker.start();
            waitUntilAllSucceeded(setup.deliveries());
            double seconds = (System.nanoTime() - start) / 1e9;
            int peakThreads = threads.getPeakThreadCount();
            benchmarkWorker.stop();

            table.append(String.format(Locale.ROOT, "| %s | %d | %,d | %.1f | %,.0f | %d |%n",
                    setup.type(), setup.maxConcurrency(), setup.deliveries(), seconds, setup.deliveries() / seconds, peakThreads));
            assertThat(received.get()).isGreaterThanOrEqualTo(setup.deliveries());
        }
        BenchmarkReport.write(table.toString());
    }

    private AppProperties.Delivery settings(Setup setup) {
        AppProperties.Delivery base = properties.delivery();
        return new AppProperties.Delivery(true, setup.type(), setup.maxConcurrency(), 100, Duration.ofMillis(20),
                Duration.ofSeconds(10), Duration.ofMinutes(5), Duration.ofHours(1), base.retry(),
                base.failureThreshold(), base.pauseDuration(), base.secretRotationGrace());
    }

    private void resetDatabase() {
        jdbc.sql("TRUNCATE delivery_attempts, deliveries, events, endpoints, applications CASCADE").update();
    }

    /** The receiver is not WireMock here, so the endpoint URL points at the slow receiver instead. */
    private long createBenchmarkEndpoint() {
        String apiKey = createApplication("benchmark");
        return as(apiKey)
                .body("{\"url\": \"http://localhost:" + slowReceiver.getAddress().getPort() + "/slow\"}")
                .post("/api/v1/endpoints")
                .then().statusCode(201)
                .extract().jsonPath().getLong("id");
    }

    private void insertDueDeliveries(long endpointId, int count) {
        long applicationId = jdbc.sql("SELECT application_id FROM endpoints WHERE id = :id")
                .param("id", endpointId).query(Long.class).single();
        jdbc.sql("""
                        INSERT INTO events (application_id, event_type, payload, created_at)
                        SELECT :app, 'benchmark.test', '{"n": ' || n || '}', now() FROM generate_series(1, :count) AS n
                        """)
                .param("app", applicationId).param("count", count).update();
        jdbc.sql("""
                        INSERT INTO deliveries (event_id, endpoint_id, application_id, status, attempt_count,
                                                next_attempt_at, version, created_at, updated_at)
                        SELECT e.id, :endpoint, :app, 'PENDING', 0, now() - interval '1 second', 0, now(), now()
                        FROM events e WHERE e.application_id = :app
                        """)
                .param("endpoint", endpointId).param("app", applicationId).update();
    }

    private void waitUntilAllSucceeded(int expected) {
        long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
        while (System.nanoTime() < deadline) {
            long done = jdbc.sql("SELECT count(*) FROM deliveries WHERE status = 'SUCCEEDED'").query(Long.class).single();
            if (done >= expected) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("Deliveries did not finish within 5 minutes");
    }
}
