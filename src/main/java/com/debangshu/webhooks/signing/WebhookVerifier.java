package com.debangshu.webhooks.signing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;

/**
 * What a RECEIVER runs to check that a webhook really came from us and wasn't changed on the way.
 * The service itself doesn't need it; it is here for the tests and as copy-paste code for receivers
 * (see "Verifying signatures" in the README).
 */
public class WebhookVerifier {

    private final Duration tolerance;

    public WebhookVerifier() {
        this(Duration.ofMinutes(5));
    }

    public WebhookVerifier(Duration tolerance) {
        this.tolerance = tolerance;
    }

    public void verify(String secret, String webhookId, String timestampHeader, String signatureHeader,
                       String body, Instant now) {
        if (webhookId == null || timestampHeader == null || signatureHeader == null) {
            throw new WebhookVerificationException("Missing webhook-id, webhook-timestamp or webhook-signature header");
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader.trim());
        } catch (NumberFormatException e) {
            throw new WebhookVerificationException("webhook-timestamp is not a number");
        }
        // reject old (or future) timestamps: stops someone from re-sending a request they recorded yesterday
        if (Math.abs(now.getEpochSecond() - timestamp) > tolerance.toSeconds()) {
            throw new WebhookVerificationException("webhook-timestamp is outside the allowed " + tolerance.toMinutes() + " minutes");
        }
        byte[] expected = WebhookSignatures.sign(secret, webhookId, timestamp, body).getBytes(StandardCharsets.UTF_8);
        for (String candidate : signatureHeader.trim().split(" +")) {
            // constant time comparison, String.equals stops at the first different character and
            // that timing difference can leak the signature byte by byte
            if (MessageDigest.isEqual(expected, candidate.getBytes(StandardCharsets.UTF_8))) {
                return;
            }
        }
        throw new WebhookVerificationException("No signature matched");
    }
}
