package com.debangshu.webhooks.delivery;

import com.debangshu.webhooks.endpoint.EndpointHealthService;
import com.debangshu.webhooks.endpoint.EndpointHealthService.FailureResult;
import com.debangshu.webhooks.sending.SendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Saves the result of one attempt and decides what happens next:
 * success -> SUCCEEDED, 410 Gone -> FAILED + endpoint disabled, anything else -> retry later or FAILED.
 *
 * Everything here runs in one transaction: the attempt row, the delivery's new state and the endpoint's
 * failure counter are saved together or not at all.
 */
@Component
public class DeliveryResultRecorder {

    private static final Logger log = LoggerFactory.getLogger(DeliveryResultRecorder.class);

    private final DeliveryRepository deliveries;
    private final DeliveryAttemptRepository attempts;
    private final EndpointHealthService endpointHealth;
    private final RetryPolicy retryPolicy;
    private final DeliveryMetrics metrics;
    private final Clock clock;

    public DeliveryResultRecorder(DeliveryRepository deliveries, DeliveryAttemptRepository attempts,
                                  EndpointHealthService endpointHealth, RetryPolicy retryPolicy,
                                  DeliveryMetrics metrics, Clock clock) {
        this.deliveries = deliveries;
        this.attempts = attempts;
        this.endpointHealth = endpointHealth;
        this.retryPolicy = retryPolicy;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional
    public void record(DeliveryTask task, SendResult result, Instant startedAt) {
        Instant now = clock.instant();
        Delivery delivery = deliveries.findById(task.deliveryId()).orElse(null);
        if (delivery == null) {
            return; // the endpoint (and its deliveries) was deleted while we were sending
        }
        if (delivery.getVersion() != task.version() || delivery.getStatus() != DeliveryStatus.SENDING) {
            // the lease ran out and the reaper gave this delivery to someone else; their result counts, not ours
            log.info("Delivery {} changed while it was being sent, ignoring this result", task.deliveryId());
            return;
        }

        attempts.save(new DeliveryAttempt(task.deliveryId(), task.endpointId(), startedAt, result.durationMs(),
                result.statusCode(), result.responseBody(), result.error(), result.outcome()));
        metrics.recordAttempt(result.outcome(), result.durationMs());

        if (result.success()) {
            delivery.markSucceeded(result.statusCode(), now);
            endpointHealth.recordSuccess(task.endpointId());
            return;
        }

        // 410 Gone = "this URL doesn't exist any more, stop sending". Retrying would be pointless.
        if (result.statusCode() != null && result.statusCode() == 410) {
            delivery.markFailed(410, "Receiver answered 410 Gone, endpoint disabled", now);
            endpointHealth.disable(task.endpointId(), "receiver answered 410 Gone");
            return;
        }

        FailureResult failure = endpointHealth.recordFailure(task.endpointId());
        Optional<Duration> delay = retryPolicy.nextDelay(delivery.getAttemptCount(), result.retryAfter());
        if (delay.isEmpty()) {
            delivery.markFailed(result.statusCode(), result.error(), now);
            log.info("Delivery {} failed after {} attempts: {}", task.deliveryId(), delivery.getAttemptCount(), result.error());
            return;
        }

        Instant nextAttempt = now.plus(delay.get());
        // if the circuit breaker just paused the endpoint, wait at least until the pause is over
        if (failure.pausedUntil() != null && failure.pausedUntil().isAfter(nextAttempt)) {
            nextAttempt = failure.pausedUntil();
        }
        delivery.scheduleRetry(nextAttempt, result.statusCode(), result.error(), now);
        // the UPDATE (with "WHERE version = ?") runs when the transaction commits
    }
}
