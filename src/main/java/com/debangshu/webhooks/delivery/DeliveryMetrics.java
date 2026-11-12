package com.debangshu.webhooks.delivery;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Custom metrics, visible at /actuator/prometheus (admin key needed):
 * - webhook_delivery_attempts_total{outcome="SUCCESS|HTTP_ERROR|TIMEOUT|NETWORK_ERROR"}
 * - webhook_delivery_duration_seconds (histogram of HTTP request times)
 * - webhook_deliveries_pending (queue depth, the first thing to alert on)
 */
@Component
public class DeliveryMetrics {

    private final MeterRegistry registry;
    private final Timer duration;

    public DeliveryMetrics(MeterRegistry registry, DeliveryQueue queue) {
        this.registry = registry;
        this.duration = Timer.builder("webhook.delivery.duration")
                .description("Time to send one webhook and get the answer")
                .publishPercentileHistogram()
                .register(registry);
        Gauge.builder("webhook.deliveries.pending", queue, DeliveryQueue::countPending)
                .description("Deliveries waiting to be sent")
                .register(registry);
    }

    public void recordAttempt(AttemptOutcome outcome, int durationMs) {
        Counter.builder("webhook.delivery.attempts")
                .tag("outcome", outcome.name())
                .register(registry)
                .increment();
        duration.record(Duration.ofMillis(durationMs));
    }
}
