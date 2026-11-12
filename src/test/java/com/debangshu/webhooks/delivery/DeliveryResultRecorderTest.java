package com.debangshu.webhooks.delivery;

import com.debangshu.webhooks.config.AppProperties;
import com.debangshu.webhooks.endpoint.EndpointHealthService;
import com.debangshu.webhooks.endpoint.EndpointHealthService.FailureResult;
import com.debangshu.webhooks.sending.SendResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeliveryResultRecorderTest {

    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");
    private static final Instant STARTED = NOW.minusMillis(120);
    private static final long DELIVERY_ID = 7L;
    private static final long ENDPOINT_ID = 3L;

    @Mock
    DeliveryRepository deliveries;
    @Mock
    DeliveryAttemptRepository attempts;
    @Mock
    EndpointHealthService endpointHealth;
    @Mock
    DeliveryMetrics metrics;

    private DeliveryResultRecorder recorder;

    @BeforeEach
    void setUp() {
        // 5s, 10s, 20s ... 4 attempts in total, no jitter
        var retry = new AppProperties.Retry(Duration.ofSeconds(5), 2.0, Duration.ofMinutes(10), 4, 0.0, Duration.ofMinutes(5));
        recorder = new DeliveryResultRecorder(deliveries, attempts, endpointHealth, new RetryPolicy(retry), metrics,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** A delivery as the worker sees it after claiming it: SENDING, attempt n, version 5. */
    private Delivery sendingDelivery(int attemptCount) {
        Delivery delivery = new Delivery(); // protected constructor, this test is in the same package
        ReflectionTestUtils.setField(delivery, "id", DELIVERY_ID);
        ReflectionTestUtils.setField(delivery, "endpointId", ENDPOINT_ID);
        ReflectionTestUtils.setField(delivery, "status", DeliveryStatus.SENDING);
        ReflectionTestUtils.setField(delivery, "attemptCount", attemptCount);
        ReflectionTestUtils.setField(delivery, "version", 5L);
        when(deliveries.findById(DELIVERY_ID)).thenReturn(Optional.of(delivery));
        return delivery;
    }

    private static DeliveryTask task(int attemptNumber) {
        return new DeliveryTask(DELIVERY_ID, 5L, attemptNumber, ENDPOINT_ID, "https://hooks.example.com/in",
                List.of("whsec_secret"), 1L, "evt_1", "invoice.paid", "{}", NOW.minusSeconds(60));
    }

    @Test
    void successMarksTheDeliverySucceededAndResetsTheEndpoint() {
        Delivery delivery = sendingDelivery(1);

        recorder.record(task(1), SendResult.response(200, "ok", null, 120), STARTED);

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.SUCCEEDED);
        assertThat(delivery.getLastResponseStatus()).isEqualTo(200);
        assertThat(delivery.getNextAttemptAt()).isNull();
        verify(endpointHealth).recordSuccess(ENDPOINT_ID);
        verify(endpointHealth, never()).recordFailure(anyLong());

        ArgumentCaptor<DeliveryAttempt> saved = ArgumentCaptor.forClass(DeliveryAttempt.class);
        verify(attempts).save(saved.capture());
        assertThat(saved.getValue().getOutcome()).isEqualTo(AttemptOutcome.SUCCESS);
        assertThat(saved.getValue().getDurationMs()).isEqualTo(120);
        assertThat(saved.getValue().getStartedAt()).isEqualTo(STARTED);
    }

    @Test
    void failureWithRetriesLeftSchedulesTheNextAttempt() {
        Delivery delivery = sendingDelivery(2);
        when(endpointHealth.recordFailure(ENDPOINT_ID)).thenReturn(new FailureResult(2, null));

        recorder.record(task(2), SendResult.response(500, "boom", null, 30), STARTED);

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(delivery.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(10)); // second retry: 5s * 2
        assertThat(delivery.getLastError()).isEqualTo("Receiver answered HTTP 500");
        assertThat(delivery.getLastResponseStatus()).isEqualTo(500);
    }

    @Test
    void lastAttemptFailingMarksTheDeliveryFailed() {
        Delivery delivery = sendingDelivery(4);
        when(endpointHealth.recordFailure(ENDPOINT_ID)).thenReturn(new FailureResult(4, null));

        recorder.record(task(4), SendResult.timeout("No response within 10000 ms", 10_000), STARTED);

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(delivery.getNextAttemptAt()).isNull();
        assertThat(delivery.getLastError()).isEqualTo("No response within 10000 ms");
    }

    @Test
    void goneDisablesTheEndpointWithoutRetrying() {
        Delivery delivery = sendingDelivery(1);

        recorder.record(task(1), SendResult.response(410, "", null, 15), STARTED);

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        verify(endpointHealth).disable(ENDPOINT_ID, "receiver answered 410 Gone");
        verify(endpointHealth, never()).recordFailure(anyLong());
    }

    @Test
    void retryWaitsUntilTheCircuitBreakerPauseIsOver() {
        Delivery delivery = sendingDelivery(1);
        Instant pausedUntil = NOW.plus(Duration.ofMinutes(5));
        when(endpointHealth.recordFailure(ENDPOINT_ID)).thenReturn(new FailureResult(10, pausedUntil));

        recorder.record(task(1), SendResult.networkError("ConnectException: Connection refused", 3), STARTED);

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(delivery.getNextAttemptAt()).isEqualTo(pausedUntil); // not NOW + 5s
    }

    @Test
    void retryAfterFromTheReceiverIsUsed() {
        Delivery delivery = sendingDelivery(1);
        when(endpointHealth.recordFailure(ENDPOINT_ID)).thenReturn(new FailureResult(1, null));

        recorder.record(task(1), SendResult.response(429, "slow down", Duration.ofSeconds(60), 8), STARTED);

        assertThat(delivery.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void resultIsIgnoredWhenTheDeliveryChangedWhileSending() {
        Delivery delivery = sendingDelivery(1);
        ReflectionTestUtils.setField(delivery, "version", 6L); // the reaper bumped the version

        recorder.record(task(1), SendResult.response(200, "ok", null, 50), STARTED);

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.SENDING);
        verify(attempts, never()).save(any());
        verifyNoInteractions(endpointHealth, metrics);
    }

    @Test
    void resultIsIgnoredWhenTheDeliveryWasDeleted() {
        when(deliveries.findById(DELIVERY_ID)).thenReturn(Optional.empty());

        recorder.record(task(1), SendResult.response(200, "ok", null, 50), STARTED);

        verifyNoInteractions(attempts, endpointHealth, metrics);
    }
}
