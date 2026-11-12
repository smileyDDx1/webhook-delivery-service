package com.debangshu.webhooks.delivery;

import com.debangshu.webhooks.IntegrationTest;
import com.debangshu.webhooks.delivery.DeliveryQueue.ClaimedDelivery;
import com.debangshu.webhooks.endpoint.EndpointHealthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The parts where several threads (or several app instances) touch the same rows.
 * The worker is paused, so the test threads are the only ones claiming.
 */
class ConcurrencyIT extends IntegrationTest {

    @Autowired
    DeliveryQueue queue;

    @Autowired
    EndpointHealthService endpointHealth;

    private long applicationId;
    private long endpointId;

    @BeforeEach
    void setUp() {
        worker.pause();
        String apiKey = createApplication("load");
        endpointId = createEndpoint(apiKey, "/hooks/load");
        applicationId = jdbc.sql("SELECT application_id FROM endpoints WHERE id = :id")
                .param("id", endpointId).query(Long.class).single();
    }

    @AfterEach
    void cleanUp() {
        worker.resume();
    }

    /** Inserts n due PENDING deliveries straight into the database (n events, one delivery each). */
    private void insertDueDeliveries(int count, String nextAttemptAt) {
        jdbc.sql("""
                        INSERT INTO events (application_id, event_type, payload, created_at)
                        SELECT :app, 'load.test', '{}', now() FROM generate_series(1, :count)
                        """)
                .param("app", applicationId).param("count", count).update();
        jdbc.sql("""
                        INSERT INTO deliveries (event_id, endpoint_id, application_id, status, attempt_count,
                                                next_attempt_at, version, created_at, updated_at)
                        SELECT e.id, :endpoint, :app, 'PENDING', 0, %s, 0, now(), now()
                        FROM events e WHERE e.application_id = :app
                        """.formatted(nextAttemptAt))
                .param("endpoint", endpointId).param("app", applicationId).update();
    }

    @Test
    void eightWorkersClaimingAtOnceNeverGetTheSameDelivery() throws Exception {
        insertDueDeliveries(2_000, "now() - interval '1 second'");
        Queue<Long> claimedIds = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService workers = Executors.newFixedThreadPool(8)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(workers.submit(() -> {
                    start.await(); // all 8 start claiming at the same moment
                    List<ClaimedDelivery> batch;
                    do {
                        batch = queue.claimDue(25, Duration.ofMinutes(1));
                        batch.forEach(claimed -> claimedIds.add(claimed.id()));
                    } while (!batch.isEmpty());
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        }

        assertThat(claimedIds).hasSize(2_000);
        assertThat(new HashSet<>(claimedIds)).hasSize(2_000); // no id was claimed twice
        long stillPending = jdbc.sql("SELECT count(*) FROM deliveries WHERE status = 'PENDING'").query(Long.class).single();
        assertThat(stillPending).isZero();
    }

    @Test
    void deliveriesThatAreNotDueYetAreNotClaimed() {
        insertDueDeliveries(5, "now() + interval '1 hour'");

        assertThat(queue.claimDue(100, Duration.ofMinutes(1))).isEmpty();
    }

    @Test
    void claimingIncrementsAttemptsAndVersion() {
        insertDueDeliveries(1, "now() - interval '1 second'");

        ClaimedDelivery claimed = queue.claimDue(10, Duration.ofMinutes(1)).getFirst();

        assertThat(claimed.attemptCount()).isEqualTo(1);
        assertThat(claimed.version()).isEqualTo(1);
        String status = jdbc.sql("SELECT status FROM deliveries WHERE id = :id").param("id", claimed.id()).query(String.class).single();
        assertThat(status).isEqualTo("SENDING");
    }

    @Test
    void reaperRequeuesDeliveriesWhoseLeaseExpired() {
        insertDueDeliveries(3, "now() - interval '1 second'");
        queue.claimDue(3, Duration.ofMinutes(1));
        // pretend the worker died 2 minutes ago
        jdbc.sql("UPDATE deliveries SET locked_until = now() - interval '2 minutes'").update();

        int requeued = queue.requeueExpiredLeases();

        assertThat(requeued).isEqualTo(3);
        long pending = jdbc.sql("SELECT count(*) FROM deliveries WHERE status = 'PENDING' AND locked_until IS NULL")
                .query(Long.class).single();
        long leaseExpiredAttempts = jdbc.sql("SELECT count(*) FROM delivery_attempts WHERE outcome = 'LEASE_EXPIRED'")
                .query(Long.class).single();
        assertThat(pending).isEqualTo(3);
        assertThat(leaseExpiredAttempts).isEqualTo(3);
    }

    @Test
    void reaperLeavesActiveLeasesAlone() {
        insertDueDeliveries(2, "now() - interval '1 second'");
        queue.claimDue(2, Duration.ofMinutes(1));

        assertThat(queue.requeueExpiredLeases()).isZero();
    }

    @Test
    void failureCounterLosesNoUpdatesUnderConcurrency() throws Exception {
        // 40 threads report a failure at the same time; a read-modify-write in Java would lose some of them
        try (ExecutorService threads = Executors.newFixedThreadPool(40)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                futures.add(threads.submit(() -> endpointHealth.recordFailure(endpointId)));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        }

        int failures = jdbc.sql("SELECT consecutive_failures FROM endpoints WHERE id = :id")
                .param("id", endpointId).query(Integer.class).single();
        assertThat(failures).isEqualTo(40);
    }

    @Test
    void pausingAnEndpointPostponesItsWaitingDeliveries() {
        insertDueDeliveries(3, "now() - interval '1 second'");

        for (int i = 0; i < 5; i++) { // failure-threshold = 5
            endpointHealth.recordFailure(endpointId);
        }

        assertThat(queue.claimDue(10, Duration.ofMinutes(1))).isEmpty();
    }
}
