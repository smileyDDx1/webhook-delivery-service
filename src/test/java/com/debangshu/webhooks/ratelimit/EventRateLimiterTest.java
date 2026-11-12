package com.debangshu.webhooks.ratelimit;

import com.debangshu.webhooks.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventRateLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-13T10:00:00Z"));
    private final EventRateLimiter limiter = new EventRateLimiter(clock);

    @Test
    void allowsABurstOfTwiceTheRate() {
        for (int i = 0; i < 10; i++) {
            limiter.checkLimit(1L, 5);
        }
        assertThatThrownBy(() -> limiter.checkLimit(1L, 5)).isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void refillsOverTime() {
        for (int i = 0; i < 10; i++) {
            limiter.checkLimit(1L, 5);
        }
        clock.advance(Duration.ofMillis(200)); // 5 per second -> one token every 200 ms

        assertThatNoException().isThrownBy(() -> limiter.checkLimit(1L, 5));
        assertThatThrownBy(() -> limiter.checkLimit(1L, 5)).isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void tellsTheCallerHowLongToWait() {
        for (int i = 0; i < 2; i++) {
            limiter.checkLimit(1L, 1);
        }

        assertThatThrownBy(() -> limiter.checkLimit(1L, 1))
                .isInstanceOfSatisfying(RateLimitExceededException.class,
                        ex -> assertThat(ex.getRetryAfter()).isEqualTo(Duration.ofSeconds(1)));
    }

    @Test
    void bucketDoesNotGrowPastItsCapacity() {
        limiter.checkLimit(1L, 2); // creates the bucket (capacity 4), 3 tokens left
        clock.advance(Duration.ofHours(1)); // a long quiet time doesn't save up thousands of tokens
        for (int i = 0; i < 4; i++) {
            limiter.checkLimit(1L, 2);
        }
        assertThatThrownBy(() -> limiter.checkLimit(1L, 2)).isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void applicationsHaveSeparateBuckets() {
        for (int i = 0; i < 2; i++) {
            limiter.checkLimit(1L, 1);
        }

        assertThatThrownBy(() -> limiter.checkLimit(1L, 1)).isInstanceOf(RateLimitExceededException.class);
        assertThatNoException().isThrownBy(() -> limiter.checkLimit(2L, 1));
    }
}
