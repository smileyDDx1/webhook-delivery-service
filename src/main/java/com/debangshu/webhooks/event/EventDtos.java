package com.debangshu.webhooks.event;

import com.debangshu.webhooks.delivery.Delivery;
import com.debangshu.webhooks.delivery.DeliveryStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

public final class EventDtos {

    /** Dot-separated names like "invoice.paid" or "customer.address.updated". */
    public static final String EVENT_TYPE = "^[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*$";

    private EventDtos() {
    }

    public record PublishEventRequest(
            @NotBlank @Size(max = 100)
            @Pattern(regexp = EVENT_TYPE, message = "must look like 'invoice.paid' (letters, digits, '_', '-', separated by dots)")
            String eventType,
            // any JSON object, it is delivered as the "data" field
            @NotNull JsonNode payload) {
    }

    public record EventResponse(Long id, String webhookId, String eventType, Instant createdAt, long deliveryCount) {

        public static EventResponse from(Event event, long deliveryCount) {
            return new EventResponse(event.getId(), event.webhookId(), event.getEventType(), event.getCreatedAt(), deliveryCount);
        }
    }

    public record EventDetailsResponse(Long id, String webhookId, String eventType, JsonNode payload,
                                       String idempotencyKey, Instant createdAt, List<DeliverySummary> deliveries) {
    }

    public record DeliverySummary(Long deliveryId, Long endpointId, DeliveryStatus status, int attemptCount) {

        public static DeliverySummary from(Delivery delivery) {
            return new DeliverySummary(delivery.getId(), delivery.getEndpointId(), delivery.getStatus(), delivery.getAttemptCount());
        }
    }

    /** replayed = true when the Idempotency-Key was seen before and the existing event is returned. */
    public record PublishResult(EventResponse event, boolean replayed) {
    }
}
