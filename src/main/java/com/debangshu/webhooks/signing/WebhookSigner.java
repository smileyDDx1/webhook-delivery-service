package com.debangshu.webhooks.signing;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Builds the three signature headers for an outgoing request. */
@Component
public class WebhookSigner {

    public static final String ID_HEADER = "webhook-id";
    public static final String TIMESTAMP_HEADER = "webhook-timestamp";
    public static final String SIGNATURE_HEADER = "webhook-signature";

    /**
     * @param secrets usually one secret; two during a secret rotation. Then the header has two
     *                space-separated signatures and the receiver accepts the request if either matches,
     *                so it can switch to the new secret whenever it is ready.
     */
    public Map<String, String> headers(String webhookId, String body, List<String> secrets, Instant now) {
        long timestamp = now.getEpochSecond();
        String signatures = secrets.stream()
                .map(secret -> WebhookSignatures.sign(secret, webhookId, timestamp, body))
                .collect(Collectors.joining(" "));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(ID_HEADER, webhookId);
        headers.put(TIMESTAMP_HEADER, String.valueOf(timestamp));
        headers.put(SIGNATURE_HEADER, signatures);
        return headers;
    }
}
