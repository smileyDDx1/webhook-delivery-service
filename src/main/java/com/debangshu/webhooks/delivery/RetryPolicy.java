package com.debangshu.webhooks.delivery;

import com.debangshu.webhooks.config.AppProperties;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Exponential backoff with jitter.
 *
 * delay before retry n = initialDelay * multiplier^(n-1), capped at maxDelay, then +/- jitter.
 * With the defaults (5s, x4, max 4h, 8 attempts): 5s, 20s, 1m20s, 5m20s, 21m20s, 1h25m, 4h.
 * So a receiver can be down for about 6 hours in total before a delivery is marked FAILED.
 *
 * Why the jitter: if an endpoint is down and 10,000 deliveries fail in the same second, without jitter
 * all 10,000 retries would also arrive in the same second and knock the receiver over again as soon as
 * it comes back ("thundering herd"). A random +/-20% spreads them out.
 */
public class RetryPolicy {

    private final Duration initialDelay;
    private final double multiplier;
    private final Duration maxDelay;
    private final int maxAttempts;
    private final double jitter;
    private final Duration maxRetryAfter;
    private final DoubleSupplier random;

    public RetryPolicy(AppProperties.Retry settings) {
        // ThreadLocalRandom because many delivery threads call this at the same time
        this(settings, () -> ThreadLocalRandom.current().nextDouble());
    }

    public RetryPolicy(AppProperties.Retry settings, DoubleSupplier random) {
        this.initialDelay = settings.initialDelay();
        this.multiplier = settings.multiplier();
        this.maxDelay = settings.maxDelay();
        this.maxAttempts = settings.maxAttempts();
        this.jitter = settings.jitter();
        this.maxRetryAfter = settings.maxRetryAfter();
        this.random = random;
    }

    /**
     * How long to wait before the next attempt, or empty when the delivery should give up.
     *
     * @param attemptsMade how many attempts were made so far, including the one that just failed
     * @param retryAfter   the receiver's Retry-After header (429 / 503), or null
     */
    public Optional<Duration> nextDelay(int attemptsMade, Duration retryAfter) {
        if (attemptsMade >= maxAttempts) {
            return Optional.empty();
        }
        Duration delay = withJitter(baseDelay(attemptsMade));
        if (retryAfter != null) {
            // the receiver told us when it can take more; respect it, but don't let it park
            // a delivery for days with "Retry-After: 999999"
            Duration capped = retryAfter.compareTo(maxRetryAfter) > 0 ? maxRetryAfter : retryAfter;
            if (capped.compareTo(delay) > 0) {
                delay = capped;
            }
        }
        return Optional.of(delay);
    }

    Duration baseDelay(int attemptsMade) {
        int exponent = Math.max(0, attemptsMade - 1);
        double millis = initialDelay.toMillis() * Math.pow(multiplier, exponent);
        if (millis >= maxDelay.toMillis()) {
            return maxDelay;
        }
        return Duration.ofMillis(Math.round(millis));
    }

    private Duration withJitter(Duration delay) {
        if (jitter == 0) {
            return delay;
        }
        // random factor between (1 - jitter) and (1 + jitter)
        double factor = 1 - jitter + random.getAsDouble() * 2 * jitter;
        return Duration.ofMillis(Math.round(delay.toMillis() * factor));
    }

    public int maxAttempts() {
        return maxAttempts;
    }
}
