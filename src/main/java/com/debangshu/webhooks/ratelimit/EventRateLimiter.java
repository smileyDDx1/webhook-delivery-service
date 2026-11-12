package com.debangshu.webhooks.ratelimit;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limits how fast each application can publish events (POST /api/v1/events), one token bucket per
 * application. Burst size is 2x the per-second rate.
 *
 * Limitation: the buckets live in this JVM's memory, so with 3 instances behind a load balancer an
 * application really gets 3x its limit. A shared limit would need Redis (in docs/future-plans.md).
 */
@Component
public class EventRateLimiter {

    private final Clock clock;
    private final Map<Long, TokenBucket> buckets = new ConcurrentHashMap<>();

    public EventRateLimiter(Clock clock) {
        this.clock = clock;
    }

    public void checkLimit(long applicationId, int eventsPerSecond) {
        TokenBucket bucket = buckets.computeIfAbsent(applicationId,
                id -> new TokenBucket(eventsPerSecond * 2, eventsPerSecond, clock));
        long waitMillis = bucket.tryTake();
        if (waitMillis > 0) {
            throw new RateLimitExceededException(Duration.ofMillis(waitMillis));
        }
    }
}
