package com.debangshu.webhooks.endpoint;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A URL that receives webhooks, owned by one application.
 */
@Entity
@Table(name = "endpoints")
@DynamicUpdate // UPDATE statements only contain the columns that changed
public class Endpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false, updatable = false)
    private Long applicationId;

    @Column(nullable = false, length = 2048)
    private String url;

    @Column(length = 255)
    private String description;

    // a postgres text[] column. Empty list = send every event type
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "event_types", nullable = false, columnDefinition = "text[]")
    private List<String> eventTypes = new ArrayList<>();

    @Column(nullable = false, length = 100)
    private String secret;

    @Column(name = "previous_secret", length = 100)
    private String previousSecret;

    @Column(name = "previous_secret_expires_at")
    private Instant previousSecretExpiresAt;

    // The health columns below are only changed with plain SQL by EndpointHealthService, because many
    // delivery threads update them at the same time ("failures = failures + 1" is atomic in SQL, a
    // read-modify-write through JPA is not). updatable = false makes sure a normal JPA save (e.g. changing
    // the url) can never write an old failure count back.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private EndpointStatus status;

    @Column(name = "consecutive_failures", nullable = false, updatable = false)
    private int consecutiveFailures;

    @Column(name = "paused_until", updatable = false)
    private Instant pausedUntil;

    @Column(name = "disabled_reason", length = 255, updatable = false)
    private String disabledReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Endpoint() {
        // for JPA
    }

    public Endpoint(Long applicationId, String url, String description, List<String> eventTypes, String secret, Instant now) {
        this.applicationId = applicationId;
        this.url = url;
        this.description = description;
        this.eventTypes = new ArrayList<>(eventTypes);
        this.secret = secret;
        this.status = EndpointStatus.ACTIVE;
        this.consecutiveFailures = 0;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(String url, String description, List<String> eventTypes, Instant now) {
        this.url = url;
        this.description = description;
        this.eventTypes = new ArrayList<>(eventTypes);
        this.updatedAt = now;
    }

    /**
     * The old secret keeps working until previousValidUntil, so the receiver has time to switch over.
     * During that time every request carries two signatures (one per secret).
     */
    public void rotateSecret(String newSecret, Instant previousValidUntil, Instant now) {
        this.previousSecret = this.secret;
        this.previousSecretExpiresAt = previousValidUntil;
        this.secret = newSecret;
        this.updatedAt = now;
    }

    public List<String> signingSecrets(Instant now) {
        if (previousSecret != null && previousSecretExpiresAt != null && previousSecretExpiresAt.isAfter(now)) {
            return List.of(secret, previousSecret);
        }
        return List.of(secret);
    }

    public EndpointState state(Instant now) {
        if (status == EndpointStatus.DISABLED) {
            return EndpointState.DISABLED;
        }
        if (pausedUntil != null && pausedUntil.isAfter(now)) {
            return EndpointState.PAUSED;
        }
        return EndpointState.ACTIVE;
    }

    /** When a new delivery for this endpoint may be tried first: now, or when the pause ends. */
    public Instant firstAttemptAt(Instant now) {
        return pausedUntil != null && pausedUntil.isAfter(now) ? pausedUntil : now;
    }

    public Long getId() {
        return id;
    }

    public Long getApplicationId() {
        return applicationId;
    }

    public String getUrl() {
        return url;
    }

    public String getDescription() {
        return description;
    }

    public List<String> getEventTypes() {
        return eventTypes;
    }

    public String getSecret() {
        return secret;
    }

    public Instant getPreviousSecretExpiresAt() {
        return previousSecretExpiresAt;
    }

    public EndpointStatus getStatus() {
        return status;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public Instant getPausedUntil() {
        return pausedUntil;
    }

    public String getDisabledReason() {
        return disabledReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
