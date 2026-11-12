package com.debangshu.webhooks.delivery;

import com.debangshu.webhooks.endpoint.Endpoint;
import com.debangshu.webhooks.event.Event;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * One event going to one endpoint. An event with 3 subscribed endpoints has 3 deliveries,
 * and each of them is retried on its own.
 */
@Entity
@Table(name = "deliveries")
public class Delivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false, updatable = false)
    private Event event;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "endpoint_id", nullable = false, updatable = false)
    private Endpoint endpoint;

    // The same two columns again, read-only, so I can get the ids without loading the event or endpoint.
    @Column(name = "event_id", insertable = false, updatable = false)
    private Long eventId;

    @Column(name = "endpoint_id", insertable = false, updatable = false)
    private Long endpointId;

    // copied from the event so the list queries for an application don't need a join
    @Column(name = "application_id", nullable = false, updatable = false)
    private Long applicationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeliveryStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "last_response_status")
    private Integer lastResponseStatus;

    @Column(name = "last_error", length = 500)
    private String lastError;

    // Optimistic locking: every UPDATE checks "WHERE version = <the version I read>". If the lease reaper
    // gave this delivery to another worker in the meantime (its SQL also does version = version + 1),
    // my late result is rejected instead of overwriting the newer state.
    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Delivery() {
        // for JPA
    }

    public Delivery(Event event, Endpoint endpoint, Long applicationId, Instant firstAttemptAt, Instant now) {
        this.event = event;
        this.endpoint = endpoint;
        this.eventId = event.getId();
        this.endpointId = endpoint.getId();
        this.applicationId = applicationId;
        this.status = DeliveryStatus.PENDING;
        this.attemptCount = 0;
        this.nextAttemptAt = firstAttemptAt;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void markSucceeded(int responseStatus, Instant now) {
        requireSending();
        this.status = DeliveryStatus.SUCCEEDED;
        this.nextAttemptAt = null;
        this.lockedUntil = null;
        this.lastAttemptAt = now;
        this.lastResponseStatus = responseStatus;
        this.lastError = null;
        this.updatedAt = now;
    }

    public void scheduleRetry(Instant nextAttemptAt, Integer responseStatus, String error, Instant now) {
        requireSending();
        this.status = DeliveryStatus.PENDING;
        this.nextAttemptAt = nextAttemptAt;
        this.lockedUntil = null;
        this.lastAttemptAt = now;
        this.lastResponseStatus = responseStatus;
        this.lastError = error;
        this.updatedAt = now;
    }

    public void markFailed(Integer responseStatus, String error, Instant now) {
        requireSending();
        this.status = DeliveryStatus.FAILED;
        this.nextAttemptAt = null;
        this.lockedUntil = null;
        this.lastAttemptAt = now;
        this.lastResponseStatus = responseStatus;
        this.lastError = error;
        this.updatedAt = now;
    }

    /** Manual retry from the API: back to the queue with a fresh set of retries. */
    public void retryManually(Instant firstAttemptAt, Instant now) {
        if (status == DeliveryStatus.PENDING || status == DeliveryStatus.SENDING) {
            throw new IllegalStateException("Delivery " + id + " is already queued");
        }
        this.status = DeliveryStatus.PENDING;
        this.attemptCount = 0;
        this.nextAttemptAt = firstAttemptAt;
        this.lockedUntil = null;
        this.updatedAt = now;
    }

    public boolean isQueued() {
        return status == DeliveryStatus.PENDING || status == DeliveryStatus.SENDING;
    }

    private void requireSending() {
        if (status != DeliveryStatus.SENDING) {
            throw new IllegalStateException("Delivery " + id + " is " + status + ", expected SENDING");
        }
    }

    public Long getId() {
        return id;
    }

    public Event getEvent() {
        return event;
    }

    public Endpoint getEndpoint() {
        return endpoint;
    }

    public Long getEventId() {
        return eventId;
    }

    public Long getEndpointId() {
        return endpointId;
    }

    public Long getApplicationId() {
        return applicationId;
    }

    public DeliveryStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public Integer getLastResponseStatus() {
        return lastResponseStatus;
    }

    public String getLastError() {
        return lastError;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
