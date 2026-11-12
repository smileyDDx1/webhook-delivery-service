package com.debangshu.webhooks.signing;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookSignatureTest {

    private static final String SECRET = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw";
    private static final String ID = "msg_p5jXN8AQM9LWM0D4loKWxJek";
    private static final long TIMESTAMP = 1614265330L;
    private static final String BODY = "{\"test\": 2432232314}";
    private static final Instant SIGNED_AT = Instant.ofEpochSecond(TIMESTAMP);

    private final WebhookVerifier verifier = new WebhookVerifier(Duration.ofMinutes(5));

    @Test
    void matchesTheStandardWebhooksTestVector() {
        // example from the Standard Webhooks spec, so receivers using any Standard Webhooks library can verify us
        assertThat(WebhookSignatures.sign(SECRET, ID, TIMESTAMP, BODY))
                .isEqualTo("v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=");
    }

    @Test
    void signerHeadersCanBeVerified() {
        Map<String, String> headers = new WebhookSigner().headers(ID, BODY, List.of(SECRET), SIGNED_AT);

        assertThat(headers).containsEntry("webhook-id", ID).containsEntry("webhook-timestamp", "1614265330");
        assertThatNoException().isThrownBy(() -> verifier.verify(SECRET, ID, headers.get("webhook-timestamp"),
                headers.get("webhook-signature"), BODY, SIGNED_AT.plusSeconds(30)));
    }

    @Test
    void changedBodyIsRejected() {
        String signature = WebhookSignatures.sign(SECRET, ID, TIMESTAMP, BODY);

        assertThatThrownBy(() -> verifier.verify(SECRET, ID, "1614265330", signature, "{\"test\": 1}", SIGNED_AT))
                .isInstanceOf(WebhookVerificationException.class)
                .hasMessageContaining("No signature matched");
    }

    @Test
    void wrongSecretIsRejected() {
        String signature = WebhookSignatures.sign(SECRET, ID, TIMESTAMP, BODY);

        assertThatThrownBy(() -> verifier.verify(WebhookSecrets.generate(), ID, "1614265330", signature, BODY, SIGNED_AT))
                .isInstanceOf(WebhookVerificationException.class);
    }

    @Test
    void oldTimestampIsRejectedEvenWithAValidSignature() {
        String signature = WebhookSignatures.sign(SECRET, ID, TIMESTAMP, BODY);

        assertThatThrownBy(() -> verifier.verify(SECRET, ID, "1614265330", signature, BODY, SIGNED_AT.plus(Duration.ofMinutes(6))))
                .isInstanceOf(WebhookVerificationException.class)
                .hasMessageContaining("outside the allowed");
    }

    @Test
    void changingTheTimestampBreaksTheSignature() {
        // an attacker replays an old request with a fresh timestamp: the signature no longer matches
        String signature = WebhookSignatures.sign(SECRET, ID, TIMESTAMP, BODY);
        String newTimestamp = String.valueOf(TIMESTAMP + 3600);

        assertThatThrownBy(() -> verifier.verify(SECRET, ID, newTimestamp, signature, BODY, SIGNED_AT.plusSeconds(3600)))
                .isInstanceOf(WebhookVerificationException.class)
                .hasMessageContaining("No signature matched");
    }

    @Test
    void duringRotationEitherSecretVerifies() {
        String newSecret = WebhookSecrets.generate();
        Map<String, String> headers = new WebhookSigner().headers(ID, BODY, List.of(newSecret, SECRET), SIGNED_AT);

        assertThat(headers.get("webhook-signature").split(" ")).hasSize(2);
        assertThatNoException().isThrownBy(() ->
                verifier.verify(SECRET, ID, headers.get("webhook-timestamp"), headers.get("webhook-signature"), BODY, SIGNED_AT));
        assertThatNoException().isThrownBy(() ->
                verifier.verify(newSecret, ID, headers.get("webhook-timestamp"), headers.get("webhook-signature"), BODY, SIGNED_AT));
    }

    @Test
    void missingHeadersAreRejected() {
        assertThatThrownBy(() -> verifier.verify(SECRET, ID, null, "v1,abc", BODY, SIGNED_AT))
                .isInstanceOf(WebhookVerificationException.class)
                .hasMessageContaining("Missing");
    }

    @Test
    void nonNumericTimestampIsRejected() {
        assertThatThrownBy(() -> verifier.verify(SECRET, ID, "yesterday", "v1,abc", BODY, SIGNED_AT))
                .isInstanceOf(WebhookVerificationException.class)
                .hasMessageContaining("not a number");
    }

    @Test
    void generatedSecretsHaveThePrefixAnd24RandomBytes() {
        String secret = WebhookSecrets.generate();

        assertThat(secret).startsWith("whsec_");
        assertThat(Base64.getDecoder().decode(secret.substring(6))).hasSize(24);
        assertThat(WebhookSecrets.generate()).isNotEqualTo(secret);
    }
}
