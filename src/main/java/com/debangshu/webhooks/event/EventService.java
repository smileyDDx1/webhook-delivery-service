package com.debangshu.webhooks.event;

import com.debangshu.webhooks.common.BadRequestException;
import com.debangshu.webhooks.common.ConflictException;
import com.debangshu.webhooks.common.NotFoundException;
import com.debangshu.webhooks.common.PageResponse;
import com.debangshu.webhooks.delivery.Delivery;
import com.debangshu.webhooks.delivery.DeliveryRepository;
import com.debangshu.webhooks.delivery.DeliveryRepository.EventDeliveryCount;
import com.debangshu.webhooks.endpoint.Endpoint;
import com.debangshu.webhooks.endpoint.EndpointRepository;
import com.debangshu.webhooks.endpoint.EndpointStatus;
import com.debangshu.webhooks.endpoint.EventTypeMatcher;
import com.debangshu.webhooks.event.EventDtos.DeliverySummary;
import com.debangshu.webhooks.event.EventDtos.EventDetailsResponse;
import com.debangshu.webhooks.event.EventDtos.EventResponse;
import com.debangshu.webhooks.event.EventDtos.PublishEventRequest;
import com.debangshu.webhooks.event.EventDtos.PublishResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.debangshu.webhooks.common.Times.utc;

@Service
public class EventService {

    static final int MAX_PAYLOAD_CHARS = 256 * 1024;

    private final EventRepository events;
    private final EndpointRepository endpoints;
    private final DeliveryRepository deliveries;
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public EventService(EventRepository events, EndpointRepository endpoints, DeliveryRepository deliveries,
                        JdbcClient jdbc, ObjectMapper objectMapper, Clock clock) {
        this.events = events;
        this.endpoints = endpoints;
        this.deliveries = deliveries;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Saves the event and creates one PENDING delivery per subscribed endpoint, in ONE transaction.
     * So either the event and all its deliveries are saved, or nothing is. There is no moment where the
     * caller got "202 Accepted" but the deliveries don't exist yet (this is the same idea as the
     * transactional outbox pattern: the database row IS the queue entry).
     */
    @Transactional
    public PublishResult publish(long applicationId, PublishEventRequest request, String idempotencyKey) {
        if (!request.payload().isObject()) {
            throw new BadRequestException("payload must be a JSON object");
        }
        String payload = objectMapper.writeValueAsString(request.payload());
        if (payload.length() > MAX_PAYLOAD_CHARS) {
            throw new BadRequestException("payload is too large (the limit is 256 KB)");
        }
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim();
        Instant now = clock.instant();

        Optional<Long> insertedId = insertEvent(applicationId, request.eventType(), payload, key, now);
        if (insertedId.isEmpty()) {
            return existingEventFor(applicationId, key, request.eventType(), payload);
        }

        long eventId = insertedId.get();
        Event event = events.getReferenceById(eventId); // just a reference for the foreign key, no SELECT
        List<Delivery> newDeliveries = new ArrayList<>();
        for (Endpoint endpoint : endpoints.findByApplicationIdAndStatus(applicationId, EndpointStatus.ACTIVE)) {
            if (EventTypeMatcher.matches(endpoint.getEventTypes(), request.eventType())) {
                newDeliveries.add(new Delivery(event, endpoint, applicationId, endpoint.firstAttemptAt(now), now));
            }
        }
        deliveries.saveAll(newDeliveries);
        return new PublishResult(new EventResponse(eventId, Event.webhookId(eventId), request.eventType(), now,
                newDeliveries.size()), false);
    }

    /**
     * Idempotency: producers retry too (timeouts, deploys...), and the same event must not be delivered twice
     * because of that. With an Idempotency-Key, the second request gets the first request's event back.
     *
     * My first idea was "SELECT by key, INSERT if not found", but two requests at the same moment can both
     * see "not found" and both insert. ON CONFLICT DO NOTHING lets Postgres handle that race: the second
     * insert waits for the first one to commit, then sees the conflict and inserts nothing (no row returned).
     */
    private Optional<Long> insertEvent(long applicationId, String eventType, String payload, String key, Instant now) {
        return jdbc.sql("""
                        INSERT INTO events (application_id, event_type, payload, idempotency_key, created_at)
                        VALUES (:applicationId, :eventType, :payload, :idempotencyKey, :createdAt)
                        ON CONFLICT (application_id, idempotency_key) DO NOTHING
                        RETURNING id
                        """)
                .param("applicationId", applicationId)
                .param("eventType", eventType)
                .param("payload", payload)
                .param("idempotencyKey", key, Types.VARCHAR)
                .param("createdAt", utc(now))
                .query(Long.class)
                .optional();
    }

    private PublishResult existingEventFor(long applicationId, String key, String eventType, String payload) {
        Event existing = events.findByApplicationIdAndIdempotencyKey(applicationId, key)
                .orElseThrow(() -> new IllegalStateException("Insert conflicted but no event has key " + key));
        if (!existing.getEventType().equals(eventType) || !existing.getPayload().equals(payload)) {
            throw new ConflictException("Idempotency-Key '" + key + "' was already used for a different event");
        }
        return new PublishResult(EventResponse.from(existing, deliveries.countByEventId(existing.getId())), true);
    }

    @Transactional(readOnly = true)
    public PageResponse<EventResponse> list(long applicationId, String eventType, Instant from, Instant to, Pageable pageable) {
        List<Specification<Event>> filters = new ArrayList<>();
        filters.add((root, query, cb) -> cb.equal(root.get("applicationId"), applicationId));
        if (eventType != null && !eventType.isBlank()) {
            filters.add((root, query, cb) -> cb.equal(root.get("eventType"), eventType));
        }
        if (from != null) {
            filters.add((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from));
        }
        if (to != null) {
            filters.add((root, query, cb) -> cb.lessThan(root.get("createdAt"), to));
        }
        Page<Event> page = events.findAll(Specification.allOf(filters), pageable);

        // one GROUP BY query for the delivery counts of the whole page. My first version called
        // countByEventId() inside map(), which is the N+1 problem: 20 events = 21 queries.
        List<Long> eventIds = page.getContent().stream().map(Event::getId).toList();
        Map<Long, Long> counts = new HashMap<>();
        if (!eventIds.isEmpty()) {
            for (EventDeliveryCount count : deliveries.countByEventIds(eventIds)) {
                counts.put(count.getEventId(), count.getTotal());
            }
        }
        return PageResponse.from(page.map(event -> EventResponse.from(event, counts.getOrDefault(event.getId(), 0L))));
    }

    @Transactional(readOnly = true)
    public EventDetailsResponse get(long applicationId, long eventId) {
        Event event = events.findByIdAndApplicationId(eventId, applicationId)
                .orElseThrow(() -> new NotFoundException("Event", eventId));
        List<DeliverySummary> summaries = deliveries.findByEventIdOrderByIdAsc(eventId).stream()
                .map(DeliverySummary::from)
                .toList();
        return new EventDetailsResponse(event.getId(), event.webhookId(), event.getEventType(),
                objectMapper.readTree(event.getPayload()), event.getIdempotencyKey(), event.getCreatedAt(), summaries);
    }
}
