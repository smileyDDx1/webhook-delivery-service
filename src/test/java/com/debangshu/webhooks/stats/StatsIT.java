package com.debangshu.webhooks.stats;

import com.debangshu.webhooks.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

class StatsIT extends IntegrationTest {

    @Test
    void endpointAndApplicationStats() {
        String apiKey = createApplication("billing");
        receiver.stubFor(post("/hooks/good").willReturn(ok()));
        receiver.stubFor(post("/hooks/bad").willReturn(serverError()));
        long good = createEndpoint(apiKey, "/hooks/good");
        createEndpoint(apiKey, "/hooks/bad");

        publish(apiKey, "invoice.paid", "{}");
        publish(apiKey, "invoice.paid", "{}");

        // both events: delivered to "good", failed after 4 attempts at "bad"
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> as(apiKey).get("/api/v1/stats")
                .then().body("deliveriesByStatus.FAILED", equalTo(2)));

        as(apiKey).get("/api/v1/endpoints/{id}/stats", good)
                .then()
                .statusCode(200)
                .body("state", equalTo("ACTIVE"))
                .body("deliveriesByStatus.SUCCEEDED", equalTo(2))
                .body("deliveriesByStatus.FAILED", equalTo(0))
                .body("last24Hours.attempts", equalTo(2))
                .body("last24Hours.successRate", equalTo(100.0f));

        as(apiKey).get("/api/v1/stats")
                .then()
                .statusCode(200)
                .body("events", equalTo(2))
                .body("eventsLast24Hours", equalTo(2))
                // "bad" may still be PAUSED by the circuit breaker (8 failures in a row), so only check DISABLED
                .body("endpointsByState.DISABLED", equalTo(0))
                .body("deliveriesByStatus.SUCCEEDED", equalTo(2))
                .body("last24Hours.attempts", equalTo(10)) // 2 good + 2 x 4 bad
                .body("last24Hours.succeeded", equalTo(2))
                .body("last24Hours.successRate", equalTo(20.0f))
                .body("last24Hours.p95DurationMs", greaterThanOrEqualTo(0));
    }

    @Test
    void statsOfAnotherApplicationsEndpointIs404() {
        String apiKey = createApplication("billing");
        long endpointId = createEndpoint(apiKey, "/hooks/a");
        String otherKey = createApplication("someone-else");

        as(otherKey).get("/api/v1/endpoints/{id}/stats", endpointId).then().statusCode(404);
    }
}
