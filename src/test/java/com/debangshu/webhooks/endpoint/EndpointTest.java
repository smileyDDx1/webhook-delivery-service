package com.debangshu.webhooks.endpoint;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EndpointTest {

    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");

    private static Endpoint endpoint() {
        return new Endpoint(1L, "https://hooks.example.com/in", "test", List.of(), "whsec_old", NOW);
    }

    @Test
    void newEndpointIsActive() {
        assertThat(endpoint().state(NOW)).isEqualTo(EndpointState.ACTIVE);
    }

    @Test
    void endpointIsPausedOnlyUntilPausedUntil() {
        Endpoint endpoint = endpoint();
        ReflectionTestUtils.setField(endpoint, "pausedUntil", NOW.plusSeconds(60));

        assertThat(endpoint.state(NOW)).isEqualTo(EndpointState.PAUSED);
        assertThat(endpoint.state(NOW.plusSeconds(61))).isEqualTo(EndpointState.ACTIVE);
        assertThat(endpoint.firstAttemptAt(NOW)).isEqualTo(NOW.plusSeconds(60));
        assertThat(endpoint.firstAttemptAt(NOW.plusSeconds(120))).isEqualTo(NOW.plusSeconds(120));
    }

    @Test
    void disabledWinsOverPaused() {
        Endpoint endpoint = endpoint();
        ReflectionTestUtils.setField(endpoint, "status", EndpointStatus.DISABLED);
        ReflectionTestUtils.setField(endpoint, "pausedUntil", NOW.plusSeconds(60));

        assertThat(endpoint.state(NOW)).isEqualTo(EndpointState.DISABLED);
    }

    @Test
    void afterRotationBothSecretsSignUntilTheGracePeriodEnds() {
        Endpoint endpoint = endpoint();

        endpoint.rotateSecret("whsec_new", NOW.plus(Duration.ofHours(24)), NOW);

        assertThat(endpoint.getSecret()).isEqualTo("whsec_new");
        assertThat(endpoint.signingSecrets(NOW.plusSeconds(10))).containsExactly("whsec_new", "whsec_old");
        assertThat(endpoint.signingSecrets(NOW.plus(Duration.ofHours(25)))).containsExactly("whsec_new");
    }
}
