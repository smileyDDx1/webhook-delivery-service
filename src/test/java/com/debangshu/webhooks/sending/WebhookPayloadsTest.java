package com.debangshu.webhooks.sending;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookPayloadsTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final WebhookPayloads payloads = new WebhookPayloads(mapper);

    @Test
    void wrapsThePayloadInTheEnvelope() {
        String body = payloads.envelope("evt_42", "invoice.paid", Instant.parse("2026-11-13T10:15:30Z"),
                "{\"invoiceId\":\"inv_1\",\"amount\":4999}");

        JsonNode json = mapper.readTree(body);
        assertThat(json.get("id").asString()).isEqualTo("evt_42");
        assertThat(json.get("type").asString()).isEqualTo("invoice.paid");
        assertThat(json.get("timestamp").asString()).isEqualTo("2026-11-13T10:15:30Z");
        assertThat(json.get("data").get("amount").asInt()).isEqualTo(4999);
    }

    @Test
    void sameInputGivesTheSameBody() {
        Instant at = Instant.parse("2026-11-13T10:15:30Z");

        assertThat(payloads.envelope("evt_1", "a.b", at, "{\"x\":1}"))
                .isEqualTo(payloads.envelope("evt_1", "a.b", at, "{\"x\":1}"));
    }
}
