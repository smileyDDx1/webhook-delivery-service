package com.debangshu.webhooks.delivery;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeliveryTest {

    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");

    private static Delivery delivery(DeliveryStatus status, int attempts) {
        Delivery delivery = new Delivery();
        ReflectionTestUtils.setField(delivery, "id", 1L);
        ReflectionTestUtils.setField(delivery, "status", status);
        ReflectionTestUtils.setField(delivery, "attemptCount", attempts);
        return delivery;
    }

    @Test
    void onlyASendingDeliveryCanGetAResult() {
        Delivery pending = delivery(DeliveryStatus.PENDING, 0);

        assertThatThrownBy(() -> pending.markSucceeded(200, NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> pending.markFailed(500, "x", NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> pending.scheduleRetry(NOW, 500, "x", NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void scheduleRetryPutsItBackInTheQueue() {
        Delivery delivery = delivery(DeliveryStatus.SENDING, 1);

        delivery.scheduleRetry(NOW.plusSeconds(5), 503, "Receiver answered HTTP 503", NOW);

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(delivery.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(delivery.getLastAttemptAt()).isEqualTo(NOW);
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        assertThat(delivery.isQueued()).isTrue();
    }

    @Test
    void manualRetryGivesAFailedDeliveryAFreshSetOfAttempts() {
        Delivery delivery = delivery(DeliveryStatus.FAILED, 8);

        delivery.retryManually(NOW, NOW);

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(delivery.getAttemptCount()).isZero();
        assertThat(delivery.getNextAttemptAt()).isEqualTo(NOW);
    }

    @Test
    void manualRetryOfAQueuedDeliveryIsRejected() {
        assertThatThrownBy(() -> delivery(DeliveryStatus.PENDING, 1).retryManually(NOW, NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> delivery(DeliveryStatus.SENDING, 1).retryManually(NOW, NOW))
                .isInstanceOf(IllegalStateException.class);
    }
}
