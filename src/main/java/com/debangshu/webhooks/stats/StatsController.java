package com.debangshu.webhooks.stats;

import com.debangshu.webhooks.security.AppPrincipal;
import com.debangshu.webhooks.stats.StatsDtos.ApplicationStats;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/stats")
@Tag(name = "Stats")
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping
    @Operation(summary = "Totals for your application: events, endpoints by state, deliveries by status, last 24h success rate")
    public ApplicationStats stats(@AuthenticationPrincipal AppPrincipal app) {
        return statsService.applicationStats(app.applicationId());
    }
}
