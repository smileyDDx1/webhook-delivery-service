package com.debangshu.webhooks.stats;

import com.debangshu.webhooks.common.NotFoundException;
import com.debangshu.webhooks.delivery.DeliveryStatus;
import com.debangshu.webhooks.endpoint.Endpoint;
import com.debangshu.webhooks.endpoint.EndpointRepository;
import com.debangshu.webhooks.endpoint.EndpointState;
import com.debangshu.webhooks.stats.StatsDtos.ApplicationStats;
import com.debangshu.webhooks.stats.StatsDtos.AttemptStats;
import com.debangshu.webhooks.stats.StatsDtos.EndpointStats;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.debangshu.webhooks.common.Times.utc;

/**
 * Numbers for dashboards. Plain SQL again, because these are aggregates (GROUP BY, FILTER, percentile_cont)
 * and not entities.
 */
@Service
public class StatsService {

    private static final Duration WINDOW = Duration.ofHours(24);

    private final JdbcClient jdbc;
    private final EndpointRepository endpoints;
    private final Clock clock;

    public StatsService(JdbcClient jdbc, EndpointRepository endpoints, Clock clock) {
        this.jdbc = jdbc;
        this.endpoints = endpoints;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public EndpointStats endpointStats(long applicationId, long endpointId) {
        Endpoint endpoint = endpoints.findByIdAndApplicationId(endpointId, applicationId)
                .orElseThrow(() -> new NotFoundException("Endpoint", endpointId));
        Instant now = clock.instant();

        Map<String, Long> byStatus = deliveriesByStatus("endpoint_id", endpointId);
        AttemptStats attempts = attemptStats("endpoint_id = :id", endpointId, now.minus(WINDOW));
        return new EndpointStats(endpointId, endpoint.state(now), byStatus, attempts);
    }

    @Transactional(readOnly = true)
    public ApplicationStats applicationStats(long applicationId) {
        Instant now = clock.instant();
        Instant since = now.minus(WINDOW);

        long events = jdbc.sql("SELECT count(*) FROM events WHERE application_id = :id")
                .param("id", applicationId).query(Long.class).single();
        long recentEvents = jdbc.sql("SELECT count(*) FROM events WHERE application_id = :id AND created_at >= :since")
                .param("id", applicationId).param("since", utc(since)).query(Long.class).single();

        Map<String, Long> endpointsByState = new LinkedHashMap<>();
        for (EndpointState state : EndpointState.values()) {
            endpointsByState.put(state.name(), 0L);
        }
        for (Endpoint endpoint : endpoints.findByApplicationId(applicationId, Pageable.unpaged())) {
            endpointsByState.merge(endpoint.state(now).name(), 1L, Long::sum);
        }

        Map<String, Long> byStatus = deliveriesByStatus("application_id", applicationId);

        // attempts have no application_id column, so join through deliveries
        AttemptStats attempts = attemptStats(
                "delivery_id IN (SELECT id FROM deliveries WHERE application_id = :id)", applicationId, since);
        return new ApplicationStats(events, recentEvents, endpointsByState, byStatus, attempts);
    }

    /**
     * FILTER (WHERE ...) counts only some rows inside one aggregate, and percentile_cont gives the p95
     * latency directly in SQL, so one query returns everything. Lease-expired rows are not real
     * HTTP attempts, so they are left out.
     */
    private AttemptStats attemptStats(String condition, long id, Instant since) {
        return jdbc.sql("""
                        SELECT count(*) AS attempts,
                               count(*) FILTER (WHERE outcome = 'SUCCESS') AS succeeded,
                               coalesce(avg(duration_ms), 0) AS avg_ms,
                               coalesce(percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms), 0) AS p95_ms
                        FROM delivery_attempts
                        WHERE %s AND started_at >= :since AND outcome <> 'LEASE_EXPIRED'
                        """.formatted(condition))
                .param("id", id)
                .param("since", utc(since))
                .query((rs, row) -> {
                    long total = rs.getLong("attempts");
                    long succeeded = rs.getLong("succeeded");
                    double rate = total == 0 ? 0.0 : Math.round(succeeded * 1000.0 / total) / 10.0;
                    return new AttemptStats(total, succeeded, rate, Math.round(rs.getDouble("avg_ms")),
                            Math.round(rs.getDouble("p95_ms")));
                })
                .single();
    }

    /** Every status is in the map, with 0 when there are no deliveries in that status. */
    private Map<String, Long> deliveriesByStatus(String column, long id) {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (DeliveryStatus status : DeliveryStatus.values()) {
            byStatus.put(status.name(), 0L);
        }
        RowCallbackHandler addRow = rs -> byStatus.put(rs.getString("status"), rs.getLong("total"));
        jdbc.sql("SELECT status, count(*) AS total FROM deliveries WHERE " + column + " = :id GROUP BY status")
                .param("id", id)
                .query(addRow);
        return byStatus;
    }
}
