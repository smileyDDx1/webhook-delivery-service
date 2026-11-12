package com.debangshu.webhooks.delivery;

import java.time.Instant;

public final class DeliveryDtos {

    private DeliveryDtos() {
    }

    public record DeliveryResponse(Long id, Long eventId, Long endpointId, DeliveryStatus status, int attemptCount,
                                   Instant nextAttemptAt, Instant lastAttemptAt, Integer lastResponseStatus,
                                   String lastError, Instant createdAt, Instant updatedAt) {

        public static DeliveryResponse from(Delivery delivery) {
            return new DeliveryResponse(delivery.getId(), delivery.getEventId(), delivery.getEndpointId(),
                    delivery.getStatus(), delivery.getAttemptCount(), delivery.getNextAttemptAt(),
                    delivery.getLastAttemptAt(), delivery.getLastResponseStatus(), delivery.getLastError(),
                    delivery.getCreatedAt(), delivery.getUpdatedAt());
        }
    }

    public record AttemptResponse(Long id, Instant startedAt, int durationMs, AttemptOutcome outcome,
                                  Integer responseStatus, String responseBody, String error) {

        public static AttemptResponse from(DeliveryAttempt attempt) {
            return new AttemptResponse(attempt.getId(), attempt.getStartedAt(), attempt.getDurationMs(),
                    attempt.getOutcome(), attempt.getResponseStatus(), attempt.getResponseBody(), attempt.getError());
        }
    }
}
