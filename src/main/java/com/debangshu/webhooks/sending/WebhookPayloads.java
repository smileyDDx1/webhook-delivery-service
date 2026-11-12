package com.debangshu.webhooks.sending;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;

/**
 * The body receivers get. The producer's payload is wrapped in a small envelope (the shape the Standard
 * Webhooks spec recommends), so the receiver always knows the event type and id without guessing:
 *
 *   {"id": "evt_42", "type": "invoice.paid", "timestamp": "2026-11-13T10:15:30Z", "data": { ...payload... }}
 */
@Component
public class WebhookPayloads {

    private final ObjectMapper objectMapper;

    public WebhookPayloads(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String envelope(String webhookId, String eventType, Instant createdAt, String payload) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("id", webhookId);
        root.put("type", eventType);
        root.put("timestamp", createdAt.toString());
        root.set("data", objectMapper.readTree(payload));
        return objectMapper.writeValueAsString(root);
    }
}
