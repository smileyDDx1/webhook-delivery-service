package com.debangshu.webhooks.application;

import com.debangshu.webhooks.IntegrationTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

class ApplicationAdminIT extends IntegrationTest {

    private JsonPath createViaAdminApi(String name) {
        return asAdmin().body("{\"name\": \"" + name + "\"}")
                .post("/api/v1/admin/applications")
                .then().statusCode(201)
                .extract().jsonPath();
    }

    @Test
    void createReturnsTheKeyOnlyOnce() {
        Response response = asAdmin().body("{\"name\": \"billing-service\"}").post("/api/v1/admin/applications");

        response.then()
                .statusCode(201)
                .body("apiKey", startsWith("whk_"))
                .body("rateLimitPerSecond", equalTo(1000)); // default from the test profile
        long id = response.jsonPath().getLong("id");
        assertThat(response.header("Location")).isEqualTo("/api/v1/admin/applications/" + id);

        asAdmin().get("/api/v1/admin/applications/{id}", id)
                .then()
                .statusCode(200)
                .body("name", equalTo("billing-service"))
                .body("apiKeyPrefix", startsWith("whk_"))
                .body("$", not(hasKey("apiKey")));
    }

    @Test
    void invalidRequestIsAValidationErrorPerField() {
        asAdmin().body("{\"name\": \"  \", \"rateLimitPerSecond\": 0}")
                .post("/api/v1/admin/applications")
                .then()
                .statusCode(400)
                .contentType("application/problem+json")
                .body("title", equalTo("Validation failed"))
                .body("errors.name", notNullValue())
                .body("errors.rateLimitPerSecond", notNullValue());
    }

    @Test
    void malformedJsonIs400() {
        asAdmin().body("{\"name\": ").post("/api/v1/admin/applications").then().statusCode(400);
    }

    @Test
    void listIsPagedNewestFirst() {
        createApplication("first");
        createApplication("second");
        createApplication("third");

        asAdmin().get("/api/v1/admin/applications?size=2")
                .then()
                .statusCode(200)
                .body("items.size()", equalTo(2))
                .body("items[0].name", equalTo("third"))
                .body("totalItems", equalTo(3))
                .body("totalPages", equalTo(2));
    }

    @Test
    void pageSizeAbove100IsRejected() {
        asAdmin().get("/api/v1/admin/applications?size=500").then().statusCode(400);
    }

    @Test
    void rotatingTheKeyInvalidatesTheOldOne() {
        JsonPath created = createViaAdminApi("billing");
        String oldKey = created.getString("apiKey");

        String newKey = asAdmin().post("/api/v1/admin/applications/{id}/api-key/rotate", created.getLong("id"))
                .then().statusCode(200)
                .extract().jsonPath().getString("apiKey");

        assertThat(newKey).isNotEqualTo(oldKey);
        as(oldKey).get("/api/v1/endpoints").then().statusCode(401);
        as(newKey).get("/api/v1/endpoints").then().statusCode(200);
    }

    @Test
    void deletingAnApplicationRemovesItsKeyAndData() {
        JsonPath created = createViaAdminApi("short-lived");
        String apiKey = created.getString("apiKey");
        long endpointId = createEndpoint(apiKey, "/hooks/a");

        asAdmin().delete("/api/v1/admin/applications/{id}", created.getLong("id")).then().statusCode(204);

        asAdmin().get("/api/v1/admin/applications/{id}", created.getLong("id")).then().statusCode(404);
        as(apiKey).get("/api/v1/endpoints").then().statusCode(401);
        long endpointsLeft = jdbc.sql("SELECT count(*) FROM endpoints WHERE id = :id")
                .param("id", endpointId).query(Long.class).single();
        assertThat(endpointsLeft).isZero();
    }

    @Test
    void unknownApplicationIs404() {
        asAdmin().get("/api/v1/admin/applications/999999").then().statusCode(404).body("title", equalTo("Not found"));
    }
}
