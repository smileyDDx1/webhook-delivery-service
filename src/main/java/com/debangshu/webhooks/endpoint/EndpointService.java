package com.debangshu.webhooks.endpoint;

import com.debangshu.webhooks.common.ConflictException;
import com.debangshu.webhooks.common.NotFoundException;
import com.debangshu.webhooks.common.PageResponse;
import com.debangshu.webhooks.config.AppProperties;
import com.debangshu.webhooks.delivery.DeliveryQueue;
import com.debangshu.webhooks.endpoint.EndpointDtos.EndpointRequest;
import com.debangshu.webhooks.endpoint.EndpointDtos.EndpointResponse;
import com.debangshu.webhooks.endpoint.EndpointDtos.EndpointSecretResponse;
import com.debangshu.webhooks.signing.WebhookSecrets;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;

@Service
public class EndpointService {

    private final EndpointRepository endpoints;
    private final EndpointUrlValidator urlValidator;
    private final EndpointHealthService health;
    private final DeliveryQueue deliveryQueue;
    private final AppProperties properties;
    private final Clock clock;

    public EndpointService(EndpointRepository endpoints, EndpointUrlValidator urlValidator, EndpointHealthService health,
                           DeliveryQueue deliveryQueue, AppProperties properties, Clock clock) {
        this.endpoints = endpoints;
        this.urlValidator = urlValidator;
        this.health = health;
        this.deliveryQueue = deliveryQueue;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public EndpointResponse create(long applicationId, EndpointRequest request) {
        String url = urlValidator.validate(request.url());
        Instant now = clock.instant();
        Endpoint endpoint = endpoints.save(new Endpoint(applicationId, url, request.description(),
                cleanEventTypes(request.eventTypes()), WebhookSecrets.generate(), now));
        return EndpointResponse.from(endpoint, now);
    }

    @Transactional(readOnly = true)
    public PageResponse<EndpointResponse> list(long applicationId, Pageable pageable) {
        Instant now = clock.instant();
        return PageResponse.from(endpoints.findByApplicationId(applicationId, pageable)
                .map(endpoint -> EndpointResponse.from(endpoint, now)));
    }

    @Transactional(readOnly = true)
    public EndpointResponse get(long applicationId, long endpointId) {
        return EndpointResponse.from(find(applicationId, endpointId), clock.instant());
    }

    @Transactional
    public EndpointResponse update(long applicationId, long endpointId, EndpointRequest request) {
        Endpoint endpoint = find(applicationId, endpointId);
        String url = urlValidator.validate(request.url());
        Instant now = clock.instant();
        endpoint.update(url, request.description(), cleanEventTypes(request.eventTypes()), now);
        return EndpointResponse.from(endpoint, now);
    }

    /** Deletes the endpoint and (through ON DELETE CASCADE) its deliveries and their attempts. */
    @Transactional
    public void delete(long applicationId, long endpointId) {
        endpoints.delete(find(applicationId, endpointId));
    }

    @Transactional(readOnly = true)
    public EndpointSecretResponse getSecret(long applicationId, long endpointId) {
        Endpoint endpoint = find(applicationId, endpointId);
        return new EndpointSecretResponse(endpoint.getSecret(), validUntil(endpoint.getPreviousSecretExpiresAt()));
    }

    @Transactional
    public EndpointSecretResponse rotateSecret(long applicationId, long endpointId) {
        Endpoint endpoint = find(applicationId, endpointId);
        Instant now = clock.instant();
        Instant previousValidUntil = now.plus(properties.delivery().secretRotationGrace());
        endpoint.rotateSecret(WebhookSecrets.generate(), previousValidUntil, now);
        return new EndpointSecretResponse(endpoint.getSecret(), previousValidUntil);
    }

    @Transactional
    public EndpointResponse disable(long applicationId, long endpointId) {
        requireExists(applicationId, endpointId);
        health.disable(endpointId, "disabled through the API");
        return get(applicationId, endpointId);
    }

    @Transactional
    public EndpointResponse enable(long applicationId, long endpointId) {
        requireExists(applicationId, endpointId);
        health.enable(endpointId);
        return get(applicationId, endpointId);
    }

    /** Puts every FAILED delivery of this endpoint back in the queue, e.g. after the receiver had an outage. */
    @Transactional
    public int replayFailed(long applicationId, long endpointId) {
        Endpoint endpoint = find(applicationId, endpointId);
        if (endpoint.getStatus() == EndpointStatus.DISABLED) {
            throw new ConflictException("Endpoint " + endpointId + " is disabled, enable it before replaying deliveries");
        }
        Instant now = clock.instant();
        return deliveryQueue.requeueFailed(endpointId, endpoint.firstAttemptAt(now), now);
    }

    private Endpoint find(long applicationId, long endpointId) {
        return endpoints.findByIdAndApplicationId(endpointId, applicationId)
                .orElseThrow(() -> new NotFoundException("Endpoint", endpointId));
    }

    private void requireExists(long applicationId, long endpointId) {
        if (!endpoints.existsByIdAndApplicationId(endpointId, applicationId)) {
            throw new NotFoundException("Endpoint", endpointId);
        }
    }

    private Instant validUntil(Instant previousSecretExpiresAt) {
        return previousSecretExpiresAt != null && previousSecretExpiresAt.isAfter(clock.instant())
                ? previousSecretExpiresAt : null;
    }

    /** Trims entries and removes duplicates, keeping the order the user gave. */
    private static List<String> cleanEventTypes(List<String> eventTypes) {
        if (eventTypes == null) {
            return List.of();
        }
        var cleaned = new LinkedHashSet<String>();
        for (String type : eventTypes) {
            cleaned.add(type.trim());
        }
        return List.copyOf(cleaned);
    }
}
