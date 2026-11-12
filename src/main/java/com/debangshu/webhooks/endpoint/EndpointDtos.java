package com.debangshu.webhooks.endpoint;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class EndpointDtos {

    /** "invoice.paid", "invoice.*" or "*" */
    public static final String EVENT_TYPE_FILTER = "^(\\*|[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*(\\.\\*)?)$";

    private EndpointDtos() {
    }

    /** Used for both create (POST) and update (PUT). */
    public record EndpointRequest(
            @NotBlank @Size(max = 2048) String url,
            @Size(max = 255) String description,
            @Size(max = 50, message = "can contain at most 50 event types")
            List<@NotBlank @Pattern(regexp = EVENT_TYPE_FILTER,
                    message = "must look like 'invoice.paid', 'invoice.*' or '*'") String> eventTypes) {
    }

    public record EndpointResponse(Long id, String url, String description, List<String> eventTypes,
                                   EndpointState state, int consecutiveFailures, Instant pausedUntil,
                                   String disabledReason, Instant createdAt, Instant updatedAt) {

        public static EndpointResponse from(Endpoint endpoint, Instant now) {
            return new EndpointResponse(endpoint.getId(), endpoint.getUrl(), endpoint.getDescription(),
                    List.copyOf(endpoint.getEventTypes()), endpoint.state(now), endpoint.getConsecutiveFailures(),
                    endpoint.getPausedUntil(), endpoint.getDisabledReason(), endpoint.getCreatedAt(), endpoint.getUpdatedAt());
        }
    }

    /** The secret is not part of EndpointResponse; it has its own endpoint so it doesn't end up in every log line. */
    public record EndpointSecretResponse(String secret, Instant previousSecretValidUntil) {
    }

    public record ReplayResponse(int requeued) {
    }
}
