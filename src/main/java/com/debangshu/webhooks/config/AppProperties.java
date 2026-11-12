package com.debangshu.webhooks.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * All of my own settings live under "app." in application.yml.
 * Records + @ConfigurationProperties give typed and validated config, so the app refuses to start
 * when something is missing (for example the admin key) instead of failing later at runtime.
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @Valid @NotNull Security security,
        @Valid @NotNull Delivery delivery,
        @Valid @NotNull RateLimit rateLimit) {

    public record Security(
            @NotBlank(message = "is missing, set the ADMIN_API_KEY environment variable") String adminApiKey,
            // false in production: endpoints may not point at localhost / private networks (SSRF protection)
            boolean allowPrivateUrls) {
    }

    public record Delivery(
            boolean workerEnabled,
            @NotNull ExecutorType executorType,
            // how many HTTP requests can be in flight at the same time
            @Min(1) int maxConcurrency,
            // how many due deliveries one poll claims from the database
            @Min(1) int batchSize,
            @NotNull Duration pollInterval,
            @NotNull Duration httpTimeout,
            // a claimed delivery belongs to this worker until the lease runs out
            @NotNull Duration leaseDuration,
            @NotNull Duration reaperInterval,
            @Valid @NotNull Retry retry,
            // circuit breaker: pause an endpoint after this many failures in a row
            @Min(1) int failureThreshold,
            @NotNull Duration pauseDuration,
            // after a secret rotation the old secret keeps signing for this long
            @NotNull Duration secretRotationGrace) {
    }

    public record Retry(
            @NotNull Duration initialDelay,
            @DecimalMin("1.0") double multiplier,
            @NotNull Duration maxDelay,
            @Min(1) int maxAttempts,
            @DecimalMin("0.0") @DecimalMax("1.0") double jitter,
            @NotNull Duration maxRetryAfter) {
    }

    public record RateLimit(@Min(1) int defaultEventsPerSecond) {
    }

    public enum ExecutorType {
        VIRTUAL,
        PLATFORM
    }
}
