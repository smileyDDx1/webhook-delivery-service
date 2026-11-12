package com.debangshu.webhooks.delivery;

import com.debangshu.webhooks.IntegrationTest;
import com.debangshu.webhooks.signing.WebhookVerifier;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;

/**
 * End to end: publish through the API, let the real worker send it to WireMock, check what arrived
 * and what the API reports afterwards. Retry delays are 200ms/400ms/800ms in the test profile.
 */
class DeliveryFlowIT extends IntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final String PAYLOAD = "{\"invoiceId\": \"inv_123\", \"amountCents\": 4999}";

    private String apiKey;

    @BeforeEach
    void setUp() {
        apiKey = createApplication("billing");
    }

    private List<LoggedRequest> requestsTo(String path) {
        return receiver.findAll(postRequestedFor(urlEqualTo(path)));
    }

    @Test
    void deliversASignedRequestTheReceiverCanVerify() {
        receiver.stubFor(post("/hooks/a").willReturn(ok()));
        long endpointId = createEndpoint(apiKey, "/hooks/a");
        long eventId = publish(apiKey, "invoice.paid", PAYLOAD);
        long deliveryId = deliveryOf(apiKey, eventId);

        await().atMost(WAIT).until(() -> deliveryStatus(apiKey, deliveryId).equals("SUCCEEDED"));

        LoggedRequest request = requestsTo("/hooks/a").getFirst();
        assertThat(request.getHeader("webhook-id")).isEqualTo("evt_" + eventId);
        assertThatNoException().isThrownBy(() -> new WebhookVerifier().verify(secretOf(apiKey, endpointId),
                request.getHeader("webhook-id"), request.getHeader("webhook-timestamp"),
                request.getHeader("webhook-signature"), request.getBodyAsString(), Instant.now()));

        JsonPath body = new JsonPath(request.getBodyAsString());
        assertThat(body.getString("type")).isEqualTo("invoice.paid");
        assertThat(body.getString("data.invoiceId")).isEqualTo("inv_123");

        as(apiKey).get("/api/v1/deliveries/{id}/attempts", deliveryId)
                .then().statusCode(200)
                .body("outcome", contains("SUCCESS"))
                .body("[0].responseStatus", equalTo(200));
    }

    @Test
    void eachSubscribedEndpointGetsItsOwnCopy() {
        receiver.stubFor(post("/hooks/a").willReturn(ok()));
        receiver.stubFor(post("/hooks/b").willReturn(ok()));
        createEndpoint(apiKey, "/hooks/a");
        createEndpoint(apiKey, "/hooks/b", "invoice.*");
        createEndpoint(apiKey, "/hooks/c", "customer.*"); // not subscribed

        publish(apiKey, "invoice.paid", PAYLOAD);

        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(requestsTo("/hooks/a")).hasSize(1);
            assertThat(requestsTo("/hooks/b")).hasSize(1);
        });
        assertThat(requestsTo("/hooks/c")).isEmpty();
    }

    @Test
    void failedAttemptsAreRetriedUntilTheReceiverRecovers() {
        // 500, 500, then 200
        receiver.stubFor(post("/hooks/flaky").inScenario("flaky").whenScenarioStateIs(STARTED)
                .willReturn(serverError().withBody("db down")).willSetStateTo("failed once"));
        receiver.stubFor(post("/hooks/flaky").inScenario("flaky").whenScenarioStateIs("failed once")
                .willReturn(serverError()).willSetStateTo("failed twice"));
        receiver.stubFor(post("/hooks/flaky").inScenario("flaky").whenScenarioStateIs("failed twice")
                .willReturn(ok()));
        createEndpoint(apiKey, "/hooks/flaky");
        long deliveryId = deliveryOf(apiKey, publish(apiKey, "invoice.paid", PAYLOAD));

        await().atMost(WAIT).until(() -> deliveryStatus(apiKey, deliveryId).equals("SUCCEEDED"));

        as(apiKey).get("/api/v1/deliveries/{id}/attempts", deliveryId)
                .then()
                .body("outcome", contains("HTTP_ERROR", "HTTP_ERROR", "SUCCESS"))
                .body("[0].responseBody", equalTo("db down"));
        as(apiKey).get("/api/v1/deliveries/{id}", deliveryId).then().body("attemptCount", equalTo(3));

        // the same webhook-id on every attempt, so the receiver can de-duplicate
        assertThat(requestsTo("/hooks/flaky")).extracting(r -> r.getHeader("webhook-id")).containsOnly("evt_" + idOfOnlyEvent());
    }

    @Test
    void deliveryFailsAfterTheLastAttemptAndCanBeRetriedByHand() {
        receiver.stubFor(post("/hooks/down").willReturn(aResponse().withStatus(503)));
        createEndpoint(apiKey, "/hooks/down");
        long deliveryId = deliveryOf(apiKey, publish(apiKey, "invoice.paid", PAYLOAD));

        await().atMost(WAIT).until(() -> deliveryStatus(apiKey, deliveryId).equals("FAILED"));
        assertThat(requestsTo("/hooks/down")).hasSize(4); // max-attempts in the test profile
        as(apiKey).get("/api/v1/deliveries?status=FAILED").then().body("totalItems", equalTo(1));

        // the receiver is fixed, the user clicks "retry"
        receiver.stubFor(post("/hooks/down").willReturn(ok()));
        as(apiKey).post("/api/v1/deliveries/{id}/retry", deliveryId).then().statusCode(200).body("status", equalTo("PENDING"));

        await().atMost(WAIT).until(() -> deliveryStatus(apiKey, deliveryId).equals("SUCCEEDED"));
        as(apiKey).get("/api/v1/deliveries/{id}/attempts", deliveryId).then().body("size()", equalTo(5));
    }

    @Test
    void retryingADeliveryThatIsStillQueuedIsAConflict() {
        worker.pause();
        createEndpoint(apiKey, "/hooks/a");
        long deliveryId = deliveryOf(apiKey, publish(apiKey, "invoice.paid", PAYLOAD));

        as(apiKey).post("/api/v1/deliveries/{id}/retry", deliveryId).then().statusCode(409);
    }

    @Test
    void retryAfterHeaderDelaysTheNextAttempt() {
        receiver.stubFor(post("/hooks/busy").inScenario("busy").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "2")).willSetStateTo("ok"));
        receiver.stubFor(post("/hooks/busy").inScenario("busy").whenScenarioStateIs("ok").willReturn(ok()));
        createEndpoint(apiKey, "/hooks/busy");
        long deliveryId = deliveryOf(apiKey, publish(apiKey, "invoice.paid", PAYLOAD));

        await().atMost(WAIT).until(() -> deliveryStatus(apiKey, deliveryId).equals("SUCCEEDED"));

        List<LoggedRequest> requests = requestsTo("/hooks/busy");
        Duration gap = Duration.between(requests.get(0).getLoggedDate().toInstant(), requests.get(1).getLoggedDate().toInstant());
        assertThat(gap).isGreaterThanOrEqualTo(Duration.ofMillis(1900)); // normal backoff would be 200 ms
    }

    @Test
    void goneDisablesTheEndpoint() {
        receiver.stubFor(post("/hooks/gone").willReturn(aResponse().withStatus(410)));
        long endpointId = createEndpoint(apiKey, "/hooks/gone");
        long deliveryId = deliveryOf(apiKey, publish(apiKey, "invoice.paid", PAYLOAD));

        await().atMost(WAIT).until(() -> deliveryStatus(apiKey, deliveryId).equals("FAILED"));

        assertThat(requestsTo("/hooks/gone")).hasSize(1); // no retries
        as(apiKey).get("/api/v1/endpoints/{id}", endpointId)
                .then()
                .body("state", equalTo("DISABLED"))
                .body("disabledReason", equalTo("receiver answered 410 Gone"));
        // new events don't go to a disabled endpoint
        as(apiKey).body("{\"eventType\": \"invoice.paid\", \"payload\": {}}")
                .post("/api/v1/events").then().statusCode(202).body("deliveryCount", equalTo(0));
    }

    @Test
    void circuitBreakerPausesAFailingEndpointAndResumesAfterThePause() {
        receiver.stubFor(post("/hooks/broken").willReturn(serverError()));
        long endpointId = createEndpoint(apiKey, "/hooks/broken");
        for (int i = 0; i < 5; i++) { // failure-threshold is 5 in the test profile
            publish(apiKey, "invoice.paid", "{\"n\": " + i + "}");
        }

        await().atMost(WAIT).until(() -> as(apiKey).get("/api/v1/endpoints/{id}", endpointId)
                .jsonPath().getString("state").equals("PAUSED"));

        // Retries that were scheduled a moment before the breaker opened can still go out right after it
        // (they were already in flight). Let those settle first. They fail too, which keeps the endpoint paused.
        sleep(Duration.ofMillis(500));

        // while paused nothing is sent, even for a new event
        int requestsWhenPaused = requestsTo("/hooks/broken").size();
        publish(apiKey, "invoice.paid", "{\"n\": 99}");
        receiver.stubFor(post("/hooks/broken").willReturn(ok())); // the receiver comes back during the pause
        sleep(Duration.ofMillis(800));
        assertThat(requestsTo("/hooks/broken")).hasSize(requestsWhenPaused);

        // after the 2s pause everything goes through and the breaker closes again
        await().atMost(WAIT).untilAsserted(() -> as(apiKey).get("/api/v1/deliveries?status=SUCCEEDED")
                .then().body("totalItems", equalTo(6)));
        as(apiKey).get("/api/v1/endpoints/{id}", endpointId)
                .then()
                .body("state", equalTo("ACTIVE"))
                .body("consecutiveFailures", equalTo(0));
    }

    @Test
    void replayFailedQueuesEveryFailedDeliveryOfTheEndpoint() {
        receiver.stubFor(post("/hooks/outage").willReturn(serverError()));
        long endpointId = createEndpoint(apiKey, "/hooks/outage");
        publish(apiKey, "invoice.paid", "{\"n\": 1}");
        publish(apiKey, "invoice.paid", "{\"n\": 2}");
        await().atMost(WAIT).untilAsserted(() -> as(apiKey).get("/api/v1/deliveries?status=FAILED")
                .then().body("totalItems", equalTo(2)));

        receiver.stubFor(post("/hooks/outage").willReturn(ok()));
        as(apiKey).post("/api/v1/endpoints/{id}/replay-failed", endpointId).then().statusCode(200).body("requeued", equalTo(2));

        await().atMost(WAIT).untilAsserted(() -> as(apiKey).get("/api/v1/deliveries?status=SUCCEEDED")
                .then().body("totalItems", equalTo(2)));
    }

    @Test
    void afterASecretRotationBothSecretsVerify() {
        receiver.stubFor(post("/hooks/a").willReturn(ok()));
        long endpointId = createEndpoint(apiKey, "/hooks/a");
        String oldSecret = secretOf(apiKey, endpointId);
        String newSecret = as(apiKey).post("/api/v1/endpoints/{id}/secret/rotate", endpointId)
                .then().statusCode(200).extract().jsonPath().getString("secret");

        publish(apiKey, "invoice.paid", PAYLOAD);
        await().atMost(WAIT).until(() -> requestsTo("/hooks/a").size() == 1);

        LoggedRequest request = requestsTo("/hooks/a").getFirst();
        assertThat(request.getHeader("webhook-signature").split(" ")).hasSize(2);
        for (String secret : List.of(oldSecret, newSecret)) {
            assertThatNoException().isThrownBy(() -> new WebhookVerifier().verify(secret,
                    request.getHeader("webhook-id"), request.getHeader("webhook-timestamp"),
                    request.getHeader("webhook-signature"), request.getBodyAsString(), Instant.now()));
        }
    }

    @Test
    void anotherApplicationCannotSeeOrRetryTheDelivery() {
        worker.pause();
        createEndpoint(apiKey, "/hooks/a");
        long deliveryId = deliveryOf(apiKey, publish(apiKey, "invoice.paid", PAYLOAD));
        String otherKey = createApplication("someone-else");

        as(otherKey).get("/api/v1/deliveries/{id}", deliveryId).then().statusCode(404);
        as(otherKey).get("/api/v1/deliveries/{id}/attempts", deliveryId).then().statusCode(404);
        as(otherKey).post("/api/v1/deliveries/{id}/retry", deliveryId).then().statusCode(404);
        as(otherKey).get("/api/v1/deliveries").then().body("totalItems", equalTo(0));
    }

    private long idOfOnlyEvent() {
        return jdbc.sql("SELECT id FROM events").query(Long.class).single();
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
