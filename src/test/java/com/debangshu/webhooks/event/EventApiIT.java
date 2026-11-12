package com.debangshu.webhooks.event;

import com.debangshu.webhooks.IntegrationTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

class EventApiIT extends IntegrationTest {

    private static final String PAYLOAD = "{\"invoiceId\": \"inv_123\", \"amountCents\": 4999}";

    private String apiKey;

    @BeforeEach
    void setUp() {
        apiKey = createApplication("billing");
        worker.pause(); // these tests are about the API, not about sending
    }

    @Test
    void publishCreatesOneDeliveryPerSubscribedEndpoint() {
        createEndpoint(apiKey, "/hooks/all");
        createEndpoint(apiKey, "/hooks/invoices", "invoice.*");
        createEndpoint(apiKey, "/hooks/customers", "customer.created");

        as(apiKey).body("{\"eventType\": \"invoice.paid\", \"payload\": " + PAYLOAD + "}")
                .post("/api/v1/events")
                .then()
                .statusCode(202)
                .header("Location", containsString("/api/v1/events/"))
                .body("eventType", equalTo("invoice.paid"))
                .body("webhookId", notNullValue())
                .body("deliveryCount", equalTo(2));
    }

    @Test
    void disabledEndpointsGetNothing() {
        createEndpoint(apiKey, "/hooks/a");
        long disabled = createEndpoint(apiKey, "/hooks/b");
        as(apiKey).post("/api/v1/endpoints/{id}/disable", disabled).then().statusCode(200);

        long eventId = publish(apiKey, "invoice.paid", PAYLOAD);

        as(apiKey).get("/api/v1/events/{id}", eventId).then().body("deliveries.size()", equalTo(1));
    }

    @Test
    void eventWithNoSubscribersIsStillStored() {
        long eventId = publish(apiKey, "invoice.paid", PAYLOAD);

        as(apiKey).get("/api/v1/events/{id}", eventId)
                .then()
                .statusCode(200)
                .body("payload.invoiceId", equalTo("inv_123"))
                .body("payload.amountCents", equalTo(4999))
                .body("deliveries.size()", equalTo(0));
    }

    @Test
    void sameIdempotencyKeyReturnsTheOriginalEvent() {
        createEndpoint(apiKey, "/hooks/a");

        long firstId = as(apiKey).header("Idempotency-Key", "order-42-paid")
                .body("{\"eventType\": \"invoice.paid\", \"payload\": " + PAYLOAD + "}")
                .post("/api/v1/events")
                .then().statusCode(202)
                .extract().jsonPath().getLong("id");

        as(apiKey).header("Idempotency-Key", "order-42-paid")
                .body("{\"eventType\": \"invoice.paid\", \"payload\": " + PAYLOAD + "}")
                .post("/api/v1/events")
                .then()
                .statusCode(200)
                .header("Idempotent-Replayed", "true")
                .body("id", equalTo((int) firstId))
                .body("deliveryCount", equalTo(1));

        assertThat(countEvents()).isEqualTo(1);
        assertThat(countDeliveries()).isEqualTo(1);
    }

    @Test
    void reusingAKeyForADifferentEventIsAConflict() {
        as(apiKey).header("Idempotency-Key", "k-1")
                .body("{\"eventType\": \"invoice.paid\", \"payload\": {\"a\": 1}}")
                .post("/api/v1/events").then().statusCode(202);

        as(apiKey).header("Idempotency-Key", "k-1")
                .body("{\"eventType\": \"invoice.paid\", \"payload\": {\"a\": 2}}")
                .post("/api/v1/events")
                .then()
                .statusCode(409)
                .body("detail", containsString("already used"));
    }

    @Test
    void theSameKeyIsFineForTwoDifferentApplications() {
        String otherKey = createApplication("crm");

        as(apiKey).header("Idempotency-Key", "shared").body("{\"eventType\": \"a.b\", \"payload\": {}}")
                .post("/api/v1/events").then().statusCode(202);
        as(otherKey).header("Idempotency-Key", "shared").body("{\"eventType\": \"a.b\", \"payload\": {}}")
                .post("/api/v1/events").then().statusCode(202);

        assertThat(countEvents()).isEqualTo(2);
    }

    @Test
    void concurrentRequestsWithTheSameKeyCreateOneEvent() throws Exception {
        createEndpoint(apiKey, "/hooks/a");
        int threads = 10;
        Callable<Response> sameRequest = () -> as(apiKey).header("Idempotency-Key", "race")
                .body("{\"eventType\": \"invoice.paid\", \"payload\": " + PAYLOAD + "}")
                .post("/api/v1/events");

        List<Response> responses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<Response>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(sameRequest));
            }
            for (Future<Response> future : futures) {
                responses.add(future.get());
            }
        }

        assertThat(responses).extracting(Response::statusCode).containsOnly(200, 202);
        assertThat(responses).filteredOn(r -> r.statusCode() == 202).hasSize(1);
        assertThat(responses).extracting(r -> r.jsonPath().getLong("id")).containsOnly(responses.get(0).jsonPath().getLong("id"));
        assertThat(countEvents()).isEqualTo(1);
        assertThat(countDeliveries()).isEqualTo(1);
    }

    @Test
    void invalidRequestsAreRejected() {
        as(apiKey).body("{\"eventType\": \"invoice paid!\", \"payload\": {}}")
                .post("/api/v1/events").then().statusCode(400).body("errors.eventType", notNullValue());
        as(apiKey).body("{\"eventType\": \"invoice.paid\"}")
                .post("/api/v1/events").then().statusCode(400).body("errors.payload", notNullValue());
        as(apiKey).body("{\"eventType\": \"invoice.paid\", \"payload\": [1, 2, 3]}")
                .post("/api/v1/events").then().statusCode(400).body("detail", containsString("JSON object"));
    }

    @Test
    void listCanBeFilteredByType() {
        publish(apiKey, "invoice.paid", "{}");
        publish(apiKey, "invoice.paid", "{}");
        publish(apiKey, "customer.created", "{}");

        as(apiKey).get("/api/v1/events?eventType=invoice.paid")
                .then()
                .statusCode(200)
                .body("totalItems", equalTo(2))
                .body("items[0].eventType", equalTo("invoice.paid"));
    }

    @Test
    void anotherApplicationCannotReadTheEvent() {
        long eventId = publish(apiKey, "invoice.paid", PAYLOAD);
        String otherKey = createApplication("someone-else");

        as(otherKey).get("/api/v1/events/{id}", eventId).then().statusCode(404);
    }

    @Test
    void publishingTooFastGets429WithRetryAfter() {
        String slowKey = createApplication("slow", 1); // 1 event per second, burst of 2

        publish(slowKey, "a.b", "{}");
        publish(slowKey, "a.b", "{}");
        as(slowKey).body("{\"eventType\": \"a.b\", \"payload\": {}}")
                .post("/api/v1/events")
                .then()
                .statusCode(429)
                .header("Retry-After", "1")
                .body("title", equalTo("Too many requests"));
    }

    private long countEvents() {
        return jdbc.sql("SELECT count(*) FROM events").query(Long.class).single();
    }

    private long countDeliveries() {
        return jdbc.sql("SELECT count(*) FROM deliveries").query(Long.class).single();
    }
}
