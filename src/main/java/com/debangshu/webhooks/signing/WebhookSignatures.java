package com.debangshu.webhooks.signing;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

/**
 * HMAC-SHA256 signatures in the Standard Webhooks format:
 *
 *   signed content = webhook-id + "." + webhook-timestamp + "." + body
 *   signature      = "v1," + base64(HMAC-SHA256(secret, signed content))
 *
 * The timestamp is part of the signed content, so an attacker who captured an old request can't
 * change the timestamp to "now" and replay it: the signature would no longer match.
 */
public final class WebhookSignatures {

    private static final String ALGORITHM = "HmacSHA256";

    private WebhookSignatures() {
    }

    public static String sign(String secret, String webhookId, long timestampSeconds, String body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM); // Mac objects are not thread safe, so a new one per call
            mac.init(new SecretKeySpec(WebhookSecrets.decode(secret), ALGORITHM));
            String signedContent = webhookId + "." + timestampSeconds + "." + body;
            byte[] digest = mac.doFinal(signedContent.getBytes(StandardCharsets.UTF_8));
            return "v1," + Base64.getEncoder().encodeToString(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not compute " + ALGORITHM, e);
        }
    }
}
