package com.debangshu.webhooks.event;

import com.debangshu.webhooks.common.PageResponse;
import com.debangshu.webhooks.event.EventDtos.EventDetailsResponse;
import com.debangshu.webhooks.event.EventDtos.EventResponse;
import com.debangshu.webhooks.event.EventDtos.PublishEventRequest;
import com.debangshu.webhooks.event.EventDtos.PublishResult;
import com.debangshu.webhooks.ratelimit.EventRateLimiter;
import com.debangshu.webhooks.security.AppPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/events")
@Tag(name = "Events", description = "Publish events and look them up")
public class EventController {

    private final EventService eventService;
    private final EventRateLimiter rateLimiter;

    public EventController(EventService eventService, EventRateLimiter rateLimiter) {
        this.eventService = eventService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping
    @Operation(summary = "Publish an event. Returns 202 once it is stored; delivery happens in the background.",
            description = "Send an Idempotency-Key header to make retries safe: repeating a request with the same key "
                    + "returns the original event (200 + Idempotent-Replayed: true) instead of creating a second one.")
    public ResponseEntity<EventResponse> publish(@AuthenticationPrincipal AppPrincipal app,
                                                 @RequestHeader(name = "Idempotency-Key", required = false)
                                                 @Size(max = 255) String idempotencyKey,
                                                 @Valid @RequestBody PublishEventRequest request) {
        rateLimiter.checkLimit(app.applicationId(), app.rateLimitPerSecond());
        PublishResult result = eventService.publish(app.applicationId(), request, idempotencyKey);
        if (result.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(result.event());
        }
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/events/" + result.event().id()))
                .body(result.event());
    }

    @GetMapping
    @Operation(summary = "List events, newest first. from/to are ISO-8601 instants, e.g. 2026-11-13T00:00:00Z")
    public PageResponse<EventResponse> list(@AuthenticationPrincipal AppPrincipal app,
                                            @RequestParam(required = false) String eventType,
                                            @RequestParam(required = false) Instant from,
                                            @RequestParam(required = false) Instant to,
                                            @RequestParam(defaultValue = "0") @Min(0) int page,
                                            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return eventService.list(app.applicationId(), eventType, from, to, PageResponse.newestFirst(page, size));
    }

    @GetMapping("/{id}")
    @Operation(summary = "One event with its payload and the status of each of its deliveries")
    public EventDetailsResponse get(@AuthenticationPrincipal AppPrincipal app, @PathVariable long id) {
        return eventService.get(app.applicationId(), id);
    }
}
