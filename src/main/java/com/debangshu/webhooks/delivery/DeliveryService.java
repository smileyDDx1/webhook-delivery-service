package com.debangshu.webhooks.delivery;

import com.debangshu.webhooks.common.ConflictException;
import com.debangshu.webhooks.common.NotFoundException;
import com.debangshu.webhooks.common.PageResponse;
import com.debangshu.webhooks.delivery.DeliveryDtos.AttemptResponse;
import com.debangshu.webhooks.delivery.DeliveryDtos.DeliveryResponse;
import com.debangshu.webhooks.endpoint.Endpoint;
import com.debangshu.webhooks.endpoint.EndpointStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** The read side of deliveries for the API, plus manual retry. */
@Service
public class DeliveryService {

    private final DeliveryRepository deliveries;
    private final DeliveryAttemptRepository attempts;
    private final Clock clock;

    public DeliveryService(DeliveryRepository deliveries, DeliveryAttemptRepository attempts, Clock clock) {
        this.deliveries = deliveries;
        this.attempts = attempts;
        this.clock = clock;
    }

    /** Every filter is optional. Specifications let me add only the WHERE conditions that were asked for. */
    @Transactional(readOnly = true)
    public PageResponse<DeliveryResponse> list(long applicationId, DeliveryStatus status, Long endpointId, Long eventId,
                                               Pageable pageable) {
        List<Specification<Delivery>> filters = new ArrayList<>();
        filters.add((root, query, cb) -> cb.equal(root.get("applicationId"), applicationId));
        if (status != null) {
            filters.add((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (endpointId != null) {
            filters.add((root, query, cb) -> cb.equal(root.get("endpointId"), endpointId));
        }
        if (eventId != null) {
            filters.add((root, query, cb) -> cb.equal(root.get("eventId"), eventId));
        }
        return PageResponse.from(deliveries.findAll(Specification.allOf(filters), pageable).map(DeliveryResponse::from));
    }

    @Transactional(readOnly = true)
    public DeliveryResponse get(long applicationId, long deliveryId) {
        return DeliveryResponse.from(find(applicationId, deliveryId));
    }

    @Transactional(readOnly = true)
    public List<AttemptResponse> attempts(long applicationId, long deliveryId) {
        find(applicationId, deliveryId); // 404 if it isn't this application's delivery
        return attempts.findByDeliveryIdOrderByIdAsc(deliveryId).stream().map(AttemptResponse::from).toList();
    }

    /** Sends a FAILED (or already SUCCEEDED) delivery again, with a fresh set of retries. */
    @Transactional
    public DeliveryResponse retry(long applicationId, long deliveryId) {
        Delivery delivery = find(applicationId, deliveryId);
        if (delivery.isQueued()) {
            throw new ConflictException("Delivery " + deliveryId + " is already waiting to be sent");
        }
        Endpoint endpoint = delivery.getEndpoint();
        if (endpoint.getStatus() == EndpointStatus.DISABLED) {
            throw new ConflictException("Endpoint " + endpoint.getId() + " is disabled, enable it before retrying");
        }
        Instant now = clock.instant();
        delivery.retryManually(endpoint.firstAttemptAt(now), now);
        return DeliveryResponse.from(delivery);
    }

    private Delivery find(long applicationId, long deliveryId) {
        return deliveries.findByIdAndApplicationId(deliveryId, applicationId)
                .orElseThrow(() -> new NotFoundException("Delivery", deliveryId));
    }
}
