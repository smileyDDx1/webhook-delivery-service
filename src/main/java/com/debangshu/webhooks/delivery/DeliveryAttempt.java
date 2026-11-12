package com.debangshu.webhooks.delivery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/** One HTTP request to an endpoint. This table is the delivery log users look at when something goes wrong. */
@Entity
@Immutable
@Table(name = "delivery_attempts")
public class DeliveryAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "delivery_id", nullable = false)
    private Long deliveryId;

    @Column(name = "endpoint_id", nullable = false)
    private Long endpointId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "duration_ms", nullable = false)
    private int durationMs;

    @Column(name = "response_status")
    private Integer responseStatus;

    // first 1000 characters of the receiver's answer, enough to see their error message
    @Column(name = "response_body", length = 1000)
    private String responseBody;

    @Column(length = 500)
    private String error;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AttemptOutcome outcome;

    protected DeliveryAttempt() {
        // for JPA
    }

    public DeliveryAttempt(Long deliveryId, Long endpointId, Instant startedAt, int durationMs, Integer responseStatus,
                           String responseBody, String error, AttemptOutcome outcome) {
        this.deliveryId = deliveryId;
        this.endpointId = endpointId;
        this.startedAt = startedAt;
        this.durationMs = durationMs;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.error = error;
        this.outcome = outcome;
    }

    public Long getId() {
        return id;
    }

    public Long getDeliveryId() {
        return deliveryId;
    }

    public Long getEndpointId() {
        return endpointId;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public int getDurationMs() {
        return durationMs;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public String getError() {
        return error;
    }

    public AttemptOutcome getOutcome() {
        return outcome;
    }
}
