package com.debangshu.webhooks.delivery;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.debangshu.webhooks.common.Times.utc;

/**
 * The deliveries table used as a work queue. I didn't add Kafka or RabbitMQ: Postgres is already here,
 * and with FOR UPDATE SKIP LOCKED it works fine as a queue at this size (see docs/questions-answers.md).
 *
 * These queries use JdbcClient (plain SQL) instead of JPA, because they need Postgres features JPQL
 * doesn't have: SKIP LOCKED, UPDATE ... RETURNING and data-modifying CTEs.
 */
@Repository
public class DeliveryQueue {

    private final JdbcClient jdbc;
    private final Clock clock;

    public DeliveryQueue(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** What the worker gets back from a claim: the version is needed later for the optimistic lock check. */
    public record ClaimedDelivery(long id, long version, int attemptCount) {
    }

    /**
     * Takes up to `limit` due deliveries and marks them SENDING with a lease, all in one statement.
     *
     * FOR UPDATE SKIP LOCKED is what makes several workers safe: rows another worker is locking right now
     * are skipped instead of waited for. So 2 app instances (or 8 threads) can poll at the same moment,
     * and each delivery is claimed by exactly one of them (DeliveryClaimIT tests this).
     *
     * attempt_count goes up here, at claim time, so an attempt still counts if the worker crashes mid-request.
     */
    public List<ClaimedDelivery> claimDue(int limit, Duration lease) {
        Instant now = clock.instant();
        return jdbc.sql("""
                        WITH due AS (
                            SELECT id FROM deliveries
                            WHERE status = 'PENDING' AND next_attempt_at <= :now
                            ORDER BY next_attempt_at
                            LIMIT :limit
                            FOR UPDATE SKIP LOCKED
                        )
                        UPDATE deliveries d
                        SET status = 'SENDING',
                            attempt_count = d.attempt_count + 1,
                            locked_until = :lockedUntil,
                            version = d.version + 1,
                            updated_at = :now
                        FROM due
                        WHERE d.id = due.id
                        RETURNING d.id, d.version, d.attempt_count
                        """)
                .param("now", utc(now))
                .param("limit", limit)
                .param("lockedUntil", utc(now.plus(lease)))
                .query((rs, row) -> new ClaimedDelivery(rs.getLong("id"), rs.getLong("version"), rs.getInt("attempt_count")))
                .list();
    }

    /**
     * Lease reaper. A delivery stuck in SENDING after its lease ran out means the worker died (crash, kill -9,
     * deploy) before saving a result. It goes back to PENDING so another worker sends it again, and an
     * attempt row is written so the gap is visible in the delivery log.
     *
     * This is why delivery is "at least once": the receiver might have got the first request before the
     * worker died, so it can see the same webhook-id twice and should de-duplicate on it.
     */
    public int requeueExpiredLeases() {
        Instant now = clock.instant();
        return jdbc.sql("""
                        WITH expired AS (
                            UPDATE deliveries
                            SET status = 'PENDING', locked_until = NULL, next_attempt_at = :now,
                                last_error = 'Worker stopped before the attempt finished (lease expired)',
                                version = version + 1, updated_at = :now
                            WHERE status = 'SENDING' AND locked_until < :now
                            RETURNING id, endpoint_id
                        )
                        INSERT INTO delivery_attempts (delivery_id, endpoint_id, started_at, duration_ms, outcome, error)
                        SELECT id, endpoint_id, :now, 0, 'LEASE_EXPIRED', 'Lease expired before a result was saved'
                        FROM expired
                        """)
                .param("now", utc(now))
                .update();
    }

    /** Bulk replay: every FAILED delivery of the endpoint goes back to PENDING with a fresh set of retries. */
    public int requeueFailed(long endpointId, Instant firstAttemptAt, Instant now) {
        return jdbc.sql("""
                        UPDATE deliveries
                        SET status = 'PENDING', attempt_count = 0, next_attempt_at = :firstAttemptAt,
                            version = version + 1, updated_at = :now
                        WHERE endpoint_id = :endpointId AND status = 'FAILED'
                        """)
                .param("endpointId", endpointId)
                .param("firstAttemptAt", utc(firstAttemptAt))
                .param("now", utc(now))
                .update();
    }

    /** For the "webhook.deliveries.pending" gauge. Cheap because of the partial index on PENDING rows. */
    public long countPending() {
        return jdbc.sql("SELECT count(*) FROM deliveries WHERE status = 'PENDING'")
                .query(Long.class)
                .single();
    }
}
