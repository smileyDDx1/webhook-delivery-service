package com.debangshu.webhooks.stats;

import com.debangshu.webhooks.endpoint.EndpointState;

import java.util.Map;

public final class StatsDtos {

    private StatsDtos() {
    }

    /** Attempts in the last 24 hours. successRate is a percentage (0-100) with one decimal. */
    public record AttemptStats(long attempts, long succeeded, double successRate, long avgDurationMs, long p95DurationMs) {
    }

    public record EndpointStats(long endpointId, EndpointState state, Map<String, Long> deliveriesByStatus,
                                AttemptStats last24Hours) {
    }

    public record ApplicationStats(long events, long eventsLast24Hours, Map<String, Long> endpointsByState,
                                   Map<String, Long> deliveriesByStatus, AttemptStats last24Hours) {
    }
}
