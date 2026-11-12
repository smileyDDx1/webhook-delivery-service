package com.debangshu.webhooks.endpoint;

import com.debangshu.webhooks.common.PageResponse;
import com.debangshu.webhooks.endpoint.EndpointDtos.EndpointRequest;
import com.debangshu.webhooks.endpoint.EndpointDtos.EndpointResponse;
import com.debangshu.webhooks.endpoint.EndpointDtos.EndpointSecretResponse;
import com.debangshu.webhooks.endpoint.EndpointDtos.ReplayResponse;
import com.debangshu.webhooks.security.AppPrincipal;
import com.debangshu.webhooks.stats.StatsDtos.EndpointStats;
import com.debangshu.webhooks.stats.StatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/endpoints")
@Tag(name = "Endpoints", description = "URLs that receive your webhooks")
public class EndpointController {

    private final EndpointService endpointService;
    private final StatsService statsService;

    public EndpointController(EndpointService endpointService, StatsService statsService) {
        this.endpointService = endpointService;
        this.statsService = statsService;
    }

    @PostMapping
    @Operation(summary = "Register an endpoint. An empty eventTypes list subscribes to every event.")
    public ResponseEntity<EndpointResponse> create(@AuthenticationPrincipal AppPrincipal app,
                                                   @Valid @RequestBody EndpointRequest request) {
        EndpointResponse created = endpointService.create(app.applicationId(), request);
        return ResponseEntity.created(URI.create("/api/v1/endpoints/" + created.id())).body(created);
    }

    @GetMapping
    public PageResponse<EndpointResponse> list(@AuthenticationPrincipal AppPrincipal app,
                                               @RequestParam(defaultValue = "0") @Min(0) int page,
                                               @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return endpointService.list(app.applicationId(), PageResponse.newestFirst(page, size));
    }

    @GetMapping("/{id}")
    public EndpointResponse get(@AuthenticationPrincipal AppPrincipal app, @PathVariable long id) {
        return endpointService.get(app.applicationId(), id);
    }

    @PutMapping("/{id}")
    public EndpointResponse update(@AuthenticationPrincipal AppPrincipal app, @PathVariable long id,
                                   @Valid @RequestBody EndpointRequest request) {
        return endpointService.update(app.applicationId(), id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AppPrincipal app, @PathVariable long id) {
        endpointService.delete(app.applicationId(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/secret")
    @Operation(summary = "The signing secret, needed by the receiver to verify signatures")
    public EndpointSecretResponse getSecret(@AuthenticationPrincipal AppPrincipal app, @PathVariable long id) {
        return endpointService.getSecret(app.applicationId(), id);
    }

    @PostMapping("/{id}/secret/rotate")
    @Operation(summary = "New signing secret. The old one keeps signing (as a second signature) for 24 hours.")
    public EndpointSecretResponse rotateSecret(@AuthenticationPrincipal AppPrincipal app, @PathVariable long id) {
        return endpointService.rotateSecret(app.applicationId(), id);
    }

    @PostMapping("/{id}/disable")
    @Operation(summary = "Stop sending to this endpoint. Waiting deliveries are marked FAILED (they can be replayed).")
    public EndpointResponse disable(@AuthenticationPrincipal AppPrincipal app, @PathVariable long id) {
        return endpointService.disable(app.applicationId(), id);
    }

    @PostMapping("/{id}/enable")
    @Operation(summary = "Turn the endpoint back on and reset its circuit breaker")
    public EndpointResponse enable(@AuthenticationPrincipal AppPrincipal app, @PathVariable long id) {
        return endpointService.enable(app.applicationId(), id);
    }

    @PostMapping("/{id}/replay-failed")
    @Operation(summary = "Queue every FAILED delivery of this endpoint again, e.g. after the receiver fixed an outage")
    public ReplayResponse replayFailed(@AuthenticationPrincipal AppPrincipal app, @PathVariable long id) {
        return new ReplayResponse(endpointService.replayFailed(app.applicationId(), id));
    }

    @GetMapping("/{id}/stats")
    @Operation(summary = "Deliveries by status, plus success rate and latency of the last 24 hours")
    public EndpointStats stats(@AuthenticationPrincipal AppPrincipal app, @PathVariable long id) {
        return statsService.endpointStats(app.applicationId(), id);
    }
}
