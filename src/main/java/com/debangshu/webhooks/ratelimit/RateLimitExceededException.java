package com.debangshu.webhooks.ratelimit;

import java.time.Duration;

public class RateLimitExceededException extends RuntimeException {

    private final Duration retryAfter;

    public RateLimitExceededException(Duration retryAfter) {
        super("Rate limit exceeded, try again in " + Math.max(1, retryAfter.toMillis()) + " ms");
        this.retryAfter = retryAfter;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }
}
