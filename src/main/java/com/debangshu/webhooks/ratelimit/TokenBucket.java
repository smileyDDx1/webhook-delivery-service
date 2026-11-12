package com.debangshu.webhooks.ratelimit;

import java.time.Clock;

/**
 * Token bucket: the bucket holds up to `capacity` tokens and refills at `refillPerSecond`.
 * Every request takes one token; no token left = rejected.
 *
 * Compared to "max N requests per fixed second", this allows short bursts (a full bucket) while still
 * holding the long-term average to refillPerSecond, and there is no burst at the edge of every second.
 */
class TokenBucket {

    private final double capacity;
    private final double refillPerMilli;
    private final Clock clock;
    private double tokens;
    private long lastRefillMillis;

    TokenBucket(int capacity, int refillPerSecond, Clock clock) {
        this.capacity = capacity;
        this.refillPerMilli = refillPerSecond / 1000.0;
        this.clock = clock;
        this.tokens = capacity; // start full
        this.lastRefillMillis = clock.millis();
    }

    /**
     * @return 0 if a token was taken, otherwise how many milliseconds until the next token is available.
     * synchronized because many request threads use the same bucket; the critical section is tiny.
     */
    synchronized long tryTake() {
        refill();
        if (tokens >= 1) {
            tokens -= 1;
            return 0;
        }
        return (long) Math.ceil((1 - tokens) / refillPerMilli);
    }

    private void refill() {
        long now = clock.millis();
        long elapsed = now - lastRefillMillis;
        if (elapsed > 0) {
            tokens = Math.min(capacity, tokens + elapsed * refillPerMilli);
            lastRefillMillis = now;
        }
    }
}
