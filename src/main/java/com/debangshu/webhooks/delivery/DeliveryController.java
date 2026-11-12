package com.debangshu.webhooks.delivery;

import com.debangshu.webhooks.common.PageResponse;
import com.debangshu.webhooks.delivery.DeliveryDtos.AttemptResponse;
import com.debangshu.webhooks.delivery.DeliveryDtos.DeliveryResponse;
import com.debangshu.webhooks.security.AppPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/deliveries")
@Tag(name = "Deliveries", description = "Delivery status, the attempt log and manual retries")
public class DeliveryController {

    private final DeliveryService deliveryService;

    public DeliveryController(DeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    @GetMapping
    @Operation(summary = "List deliveries, newest first. Filter by status=FAILED to see your dead letters.")
    public PageResponse<DeliveryResponse> list(@AuthenticationPrincipal AppPrincipal app,
                                               @RequestParam(required = false) DeliveryStatus status,
                                               @RequestParam(required = false) Long endpointId,
                                               @RequestParam(required = false) Long eventId,
                                               @RequestParam(defaultValue = "0") @Min(0) int page,
                                               @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return deliveryService.list(app.applicationId(), status, endpointId, eventId, PageResponse.newestFirst(page, size));
    }

    @GetMapping("/{id}")
    public DeliveryResponse get(@AuthenticationPrincipal AppPrincipal app, @PathVariable long id) {
        return deliveryService.get(app.applicationId(), id);
    }

    @GetMapping("/{id}/attempts")
    @Operation(summary = "Every HTTP attempt for this delivery: time, duration, status code and the start of the response")
    public List<AttemptResponse> attempts(@AuthenticationPrincipal AppPrincipal app, @PathVariable long id) {
        return deliveryService.attempts(app.applicationId(), id);
    }

    @PostMapping("/{id}/retry")
    @Operation(summary = "Send a FAILED or SUCCEEDED delivery again, with a fresh set of retries")
    public DeliveryResponse retry(@AuthenticationPrincipal AppPrincipal app, @PathVariable long id) {
        return deliveryService.retry(app.applicationId(), id);
    }
}
