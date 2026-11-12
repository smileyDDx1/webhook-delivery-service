package com.debangshu.webhooks.application;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Something that publishes events, for example "billing-service". Every application has its own API key
 * and its own endpoints, and can only see its own events and deliveries.
 */
@Entity
@Table(name = "applications")
public class Application {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "api_key_prefix", nullable = false, length = 16)
    private String apiKeyPrefix;

    // only the SHA-256 hash is stored, the key itself is shown once when it is created
    @Column(name = "api_key_hash", nullable = false, length = 64)
    private String apiKeyHash;

    @Column(name = "rate_limit_per_second", nullable = false)
    private int rateLimitPerSecond;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Application() {
        // for JPA
    }

    public Application(String name, int rateLimitPerSecond, String apiKeyPrefix, String apiKeyHash, Instant now) {
        this.name = name;
        this.rateLimitPerSecond = rateLimitPerSecond;
        this.apiKeyPrefix = apiKeyPrefix;
        this.apiKeyHash = apiKeyHash;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void replaceApiKey(String apiKeyPrefix, String apiKeyHash, Instant now) {
        this.apiKeyPrefix = apiKeyPrefix;
        this.apiKeyHash = apiKeyHash;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getApiKeyPrefix() {
        return apiKeyPrefix;
    }

    public String getApiKeyHash() {
        return apiKeyHash;
    }

    public int getRateLimitPerSecond() {
        return rateLimitPerSecond;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
