package com.debangshu.webhooks.endpoint;

import com.debangshu.webhooks.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

class EndpointApiIT extends IntegrationTest {

    private String apiKey;

    @BeforeEach
    void createApp() {
        apiKey = createApplication("billing");
    }

    @Test
    void createAndGet() {
        long id = createEndpoint(apiKey, "/hooks/invoices", "invoice.*", "customer.created");

        as(apiKey).get("/api/v1/endpoints/{id}", id)
                .then()
                .statusCode(200)
                .body("url", equalTo(receiverUrl("/hooks/invoices")))
                .body("eventTypes", contains("invoice.*", "customer.created"))
                .body("state", equalTo("ACTIVE"))
                .body("consecutiveFailures", equalTo(0))
                .body("$", not(hasKey("secret"))); // the secret has its own endpoint
    }

    @Test
    void duplicateEventTypesAreRemoved() {
        long id = createEndpoint(apiKey, "/hooks/a", "invoice.paid", "invoice.paid");

        as(apiKey).get("/api/v1/endpoints/{id}", id).then().body("eventTypes", contains("invoice.paid"));
    }

    @Test
    void invalidUrlIsRejected() {
        as(apiKey).body("{\"url\": \"ftp://files.example.com/in\"}")
                .post("/api/v1/endpoints")
                .then()
                .statusCode(400)
                .body("detail", containsString("http:// or https://"));
    }

    @Test
    void invalidEventTypeFilterIsRejected() {
        as(apiKey).body("{\"url\": \"" + receiverUrl("/x") + "\", \"eventTypes\": [\"invoice paid!\"]}")
                .post("/api/v1/endpoints")
                .then()
                .statusCode(400)
                .body("title", equalTo("Validation failed"));
    }

    @Test
    void update() {
        long id = createEndpoint(apiKey, "/hooks/old");

        as(apiKey).body("{\"url\": \"" + receiverUrl("/hooks/new") + "\", \"description\": \"moved\", \"eventTypes\": [\"order.*\"]}")
                .put("/api/v1/endpoints/{id}", id)
                .then()
                .statusCode(200)
                .body("url", equalTo(receiverUrl("/hooks/new")))
                .body("description", equalTo("moved"))
                .body("eventTypes", contains("order.*"));
    }

    @Test
    void delete() {
        long id = createEndpoint(apiKey, "/hooks/a");

        as(apiKey).delete("/api/v1/endpoints/{id}", id).then().statusCode(204);
        as(apiKey).get("/api/v1/endpoints/{id}", id).then().statusCode(404);
    }

    @Test
    void anotherApplicationCannotSeeOrChangeTheEndpoint() {
        long id = createEndpoint(apiKey, "/hooks/a");
        String otherKey = createApplication("someone-else");

        as(otherKey).get("/api/v1/endpoints/{id}", id).then().statusCode(404);
        as(otherKey).get("/api/v1/endpoints/{id}/secret", id).then().statusCode(404);
        as(otherKey).body("{\"url\": \"" + receiverUrl("/stolen") + "\"}").put("/api/v1/endpoints/{id}", id).then().statusCode(404);
        as(otherKey).delete("/api/v1/endpoints/{id}", id).then().statusCode(404);
        as(otherKey).get("/api/v1/endpoints").then().statusCode(200).body("totalItems", equalTo(0));
    }

    @Test
    void secretCanBeReadAndRotated() {
        long id = createEndpoint(apiKey, "/hooks/a");
        String firstSecret = secretOf(apiKey, id);

        as(apiKey).post("/api/v1/endpoints/{id}/secret/rotate", id)
                .then()
                .statusCode(200)
                .body("secret", startsWith("whsec_"))
                .body("secret", not(equalTo(firstSecret)))
                .body("previousSecretValidUntil", notNullValue());

        as(apiKey).get("/api/v1/endpoints/{id}/secret", id)
                .then()
                .body("secret", not(equalTo(firstSecret)))
                .body("previousSecretValidUntil", notNullValue());
    }

    @Test
    void newEndpointHasNoPreviousSecret() {
        long id = createEndpoint(apiKey, "/hooks/a");

        as(apiKey).get("/api/v1/endpoints/{id}/secret", id)
                .then()
                .body("secret", startsWith("whsec_"))
                .body("previousSecretValidUntil", nullValue());
    }

    @Test
    void disableAndEnable() {
        long id = createEndpoint(apiKey, "/hooks/a");

        as(apiKey).post("/api/v1/endpoints/{id}/disable", id)
                .then().statusCode(200)
                .body("state", equalTo("DISABLED"))
                .body("disabledReason", equalTo("disabled through the API"));

        as(apiKey).post("/api/v1/endpoints/{id}/enable", id)
                .then().statusCode(200)
                .body("state", equalTo("ACTIVE"))
                .body("disabledReason", nullValue());
    }

    @Test
    void replayOfADisabledEndpointIsAConflict() {
        long id = createEndpoint(apiKey, "/hooks/a");
        as(apiKey).post("/api/v1/endpoints/{id}/disable", id).then().statusCode(200);

        as(apiKey).post("/api/v1/endpoints/{id}/replay-failed", id).then().statusCode(409);
    }

    @Test
    void listIsPaged() {
        createEndpoint(apiKey, "/hooks/1");
        createEndpoint(apiKey, "/hooks/2");
        createEndpoint(apiKey, "/hooks/3");

        as(apiKey).get("/api/v1/endpoints?page=1&size=2")
                .then()
                .statusCode(200)
                .body("items.size()", equalTo(1))
                .body("items[0].url", equalTo(receiverUrl("/hooks/1")))
                .body("page", equalTo(1))
                .body("totalItems", equalTo(3));
    }
}
