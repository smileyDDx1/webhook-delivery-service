package com.debangshu.webhooks.endpoint;

import com.debangshu.webhooks.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

import static com.debangshu.webhooks.common.Times.instant;
import static com.debangshu.webhooks.common.Times.utc;

/**
 * The circuit breaker for endpoints.
 *
 * Every failed attempt adds 1 to consecutive_failures, every success sets it back to 0. When the count
 * reaches app.delivery.failure-threshold the endpoint is paused for app.delivery.pause-duration: its
 * pending deliveries are moved to the end of the pause, so a receiver that is down doesn't get hammered
 * with requests that will fail anyway, and we don't burn retry attempts on it. After the pause the next
 * delivery is a "test": success closes the breaker, another failure pauses the endpoint again.
 *
 * All of this is plain SQL on purpose. Up to max-concurrency threads can finish deliveries for the same
 * endpoint at the same moment, and "SET consecutive_failures = consecutive_failures + 1" is atomic in the
 * database. Loading the entity, adding 1 in Java and saving it would lose updates.
 */
@Service
public class EndpointHealthService {

    private static final Logger log = LoggerFactory.getLogger(EndpointHealthService.class);

    private final JdbcClient jdbc;
    private final AppProperties.Delivery settings;
    private final Clock clock;

    public EndpointHealthService(JdbcClient jdbc, AppProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.settings = properties.delivery();
        this.clock = clock;
    }

    /** Result of recording a failure: the new failure count, and the end of the pause if the endpoint is paused. */
    public record FailureResult(int consecutiveFailures, Instant pausedUntil) {
    }

    @Transactional
    public void recordSuccess(long endpointId) {
        // the WHERE clause skips the write when there is nothing to reset. A busy endpoint has many
        // successes per second and writing the same row every time would just create lock contention.
        jdbc.sql("""
                        UPDATE endpoints
                        SET consecutive_failures = 0, paused_until = NULL, updated_at = :now
                        WHERE id = :id AND (consecutive_failures <> 0 OR paused_until IS NOT NULL)
                        """)
                .param("id", endpointId)
                .param("now", utc(clock.instant()))
                .update();
    }

    @Transactional
    public FailureResult recordFailure(long endpointId) {
        Instant now = clock.instant();
        Instant pauseEnd = now.plus(settings.pauseDuration());
        // in Postgres the right side of SET sees the OLD row, so "consecutive_failures + 1" is the new count
        FailureResult result = jdbc.sql("""
                        UPDATE endpoints
                        SET consecutive_failures = consecutive_failures + 1,
                            paused_until = CASE WHEN consecutive_failures + 1 >= :threshold
                                                THEN CAST(:pauseEnd AS timestamptz)
                                                ELSE paused_until END,
                            updated_at = :now
                        WHERE id = :id
                        RETURNING consecutive_failures, paused_until
                        """)
                .param("id", endpointId)
                .param("threshold", settings.failureThreshold())
                .param("pauseEnd", utc(pauseEnd))
                .param("now", utc(now))
                .query((rs, row) -> new FailureResult(rs.getInt("consecutive_failures"), instant(rs, "paused_until")))
                .optional()
                .orElse(new FailureResult(0, null)); // endpoint was deleted in the meantime

        if (result.pausedUntil() != null && result.pausedUntil().isAfter(now)) {
            if (result.consecutiveFailures() == settings.failureThreshold()) {
                log.warn("Endpoint {} failed {} times in a row, pausing it until {}",
                        endpointId, result.consecutiveFailures(), result.pausedUntil());
            }
            postponePendingDeliveries(endpointId, result.pausedUntil(), now);
        }
        return result;
    }

    /** Turns the endpoint off. Its waiting deliveries are marked FAILED so they can be replayed later. */
    @Transactional
    public int disable(long endpointId, String reason) {
        Instant now = clock.instant();
        jdbc.sql("""
                        UPDATE endpoints
                        SET status = 'DISABLED', disabled_reason = :reason, paused_until = NULL, updated_at = :now
                        WHERE id = :id
                        """)
                .param("id", endpointId)
                .param("reason", reason)
                .param("now", utc(now))
                .update();
        int failed = jdbc.sql("""
                        UPDATE deliveries
                        SET status = 'FAILED', next_attempt_at = NULL, last_error = :error,
                            version = version + 1, updated_at = :now
                        WHERE endpoint_id = :id AND status = 'PENDING'
                        """)
                .param("id", endpointId)
                .param("error", "Endpoint disabled: " + reason)
                .param("now", utc(now))
                .update();
        log.warn("Endpoint {} disabled ({}), {} waiting deliveries marked as failed", endpointId, reason, failed);
        return failed;
    }

    /** Turns the endpoint back on, closes the circuit breaker and sends everything that is waiting right away. */
    @Transactional
    public void enable(long endpointId) {
        Instant now = clock.instant();
        jdbc.sql("""
                        UPDATE endpoints
                        SET status = 'ACTIVE', disabled_reason = NULL, consecutive_failures = 0,
                            paused_until = NULL, updated_at = :now
                        WHERE id = :id
                        """)
                .param("id", endpointId)
                .param("now", utc(now))
                .update();
        jdbc.sql("""
                        UPDATE deliveries
                        SET next_attempt_at = :now, version = version + 1, updated_at = :now
                        WHERE endpoint_id = :id AND status = 'PENDING' AND next_attempt_at > :now
                        """)
                .param("id", endpointId)
                .param("now", utc(now))
                .update();
    }

    private void postponePendingDeliveries(long endpointId, Instant pausedUntil, Instant now) {
        jdbc.sql("""
                        UPDATE deliveries
                        SET next_attempt_at = :pausedUntil, version = version + 1, updated_at = :now
                        WHERE endpoint_id = :id AND status = 'PENDING' AND next_attempt_at < :pausedUntil
                        """)
                .param("id", endpointId)
                .param("pausedUntil", utc(pausedUntil))
                .param("now", utc(now))
                .update();
    }
}
