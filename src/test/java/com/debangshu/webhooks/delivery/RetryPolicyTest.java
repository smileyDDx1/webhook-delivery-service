package com.debangshu.webhooks.delivery;

import com.debangshu.webhooks.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {

    // 5s, x4, max 4h, 8 attempts, no jitter -> exact numbers are easy to check
    private static final AppProperties.Retry DEFAULTS = new AppProperties.Retry(
            Duration.ofSeconds(5), 4.0, Duration.ofHours(4), 8, 0.0, Duration.ofHours(1));

    private final RetryPolicy policy = new RetryPolicy(DEFAULTS);

    @ParameterizedTest(name = "after attempt {0} wait {1} s")
    @CsvSource({
            "1, 5",
            "2, 20",
            "3, 80",
            "4, 320",
            "5, 1280",
            "6, 5120",
            "7, 14400" // 20480 s would be more than the 4 h cap
    })
    void delayGrowsExponentiallyUpToTheCap(int attemptsMade, long expectedSeconds) {
        assertThat(policy.nextDelay(attemptsMade, null)).contains(Duration.ofSeconds(expectedSeconds));
    }

    @Test
    void givesUpAfterMaxAttempts() {
        assertThat(policy.nextDelay(8, null)).isEmpty();
        assertThat(policy.nextDelay(9, null)).isEmpty();
    }

    @Test
    void maxAttemptsOfOneMeansNoRetries() {
        var noRetries = new RetryPolicy(new AppProperties.Retry(
                Duration.ofSeconds(5), 2.0, Duration.ofMinutes(1), 1, 0.0, Duration.ofHours(1)));

        assertThat(noRetries.nextDelay(1, null)).isEmpty();
    }

    @Test
    void jitterStaysWithinTheConfiguredRange() {
        var withJitter = new AppProperties.Retry(Duration.ofSeconds(10), 2.0, Duration.ofHours(1), 5, 0.2, Duration.ofHours(1));

        // the random source returns 0.0 (lowest) and 0.999 (highest) -> 8 s and almost 12 s
        assertThat(new RetryPolicy(withJitter, () -> 0.0).nextDelay(1, null)).contains(Duration.ofSeconds(8));
        assertThat(new RetryPolicy(withJitter, () -> 0.999).nextDelay(1, null).orElseThrow())
                .isGreaterThan(Duration.ofMillis(11_900))
                .isLessThanOrEqualTo(Duration.ofSeconds(12));
        assertThat(new RetryPolicy(withJitter, () -> 0.5).nextDelay(1, null)).contains(Duration.ofSeconds(10));
    }

    @Test
    void retryAfterLongerThanBackoffIsRespected() {
        assertThat(policy.nextDelay(1, Duration.ofSeconds(90))).contains(Duration.ofSeconds(90));
    }

    @Test
    void retryAfterShorterThanBackoffDoesNotMakeUsRetryEarlier() {
        assertThat(policy.nextDelay(3, Duration.ofSeconds(1))).contains(Duration.ofSeconds(80));
    }

    @Test
    void hugeRetryAfterIsCapped() {
        assertThat(policy.nextDelay(1, Duration.ofDays(30))).contains(Duration.ofHours(1));
    }

    @Test
    void retryAfterDoesNotExtendTheNumberOfAttempts() {
        assertThat(policy.nextDelay(8, Duration.ofSeconds(10))).isEmpty();
    }
}
