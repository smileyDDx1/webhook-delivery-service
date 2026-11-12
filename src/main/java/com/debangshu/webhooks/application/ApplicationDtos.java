package com.debangshu.webhooks.application;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Request and response records for the admin API. Records are immutable and give me
 * equals/hashCode/toString for free, which is all a DTO needs.
 */
public final class ApplicationDtos {

    private ApplicationDtos() {
    }

    public record CreateApplicationRequest(
            @NotBlank @Size(max = 100) String name,
            // optional, the default comes from app.rate-limit.default-events-per-second
            @Min(1) @Max(10_000) Integer rateLimitPerSecond) {
    }

    public record ApplicationResponse(Long id, String name, String apiKeyPrefix, int rateLimitPerSecond,
                                      Instant createdAt, Instant updatedAt) {

        public static ApplicationResponse from(Application application) {
            return new ApplicationResponse(application.getId(), application.getName(), application.getApiKeyPrefix(),
                    application.getRateLimitPerSecond(), application.getCreatedAt(), application.getUpdatedAt());
        }
    }

    /** Only returned when a key is created or rotated. After that the key can't be read again. */
    public record ApplicationKeyResponse(Long id, String name, String apiKey, String apiKeyPrefix,
                                         int rateLimitPerSecond, Instant createdAt) {

        public static ApplicationKeyResponse from(Application application, String apiKey) {
            return new ApplicationKeyResponse(application.getId(), application.getName(), apiKey,
                    application.getApiKeyPrefix(), application.getRateLimitPerSecond(), application.getCreatedAt());
        }
    }
}
