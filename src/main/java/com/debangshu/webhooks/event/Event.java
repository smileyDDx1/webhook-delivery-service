package com.debangshu.webhooks.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/**
 * One published event. It never changes after it is created (@Immutable tells Hibernate to skip dirty
 * checking for it). New events are inserted with plain SQL in EventService, see the comment there.
 */
@Entity
@Immutable
@Table(name = "events")
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false)
    private Long applicationId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    // stored as text (not jsonb): I never query inside payloads, and this exact text is what gets delivered
    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "idempotency_key", length = 255)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Event() {
        // for JPA
    }

    /** The id receivers see in the "webhook-id" header. It stays the same on every retry, so they can de-duplicate. */
    public String webhookId() {
        return webhookId(id);
    }

    public static String webhookId(long eventId) {
        return "evt_" + eventId;
    }

    public Long getId() {
        return id;
    }

    public Long getApplicationId() {
        return applicationId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
