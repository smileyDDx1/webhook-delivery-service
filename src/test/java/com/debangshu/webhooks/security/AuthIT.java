package com.debangshu.webhooks.security;

import com.debangshu.webhooks.IntegrationTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

class AuthIT extends IntegrationTest {

    @Test
    void healthAndApiDocsArePublic() {
        given().get("/actuator/health").then().statusCode(200).body("status", equalTo("UP"));
        given().get("/v3/api-docs").then().statusCode(200).body("info.title", equalTo("Webhook Delivery Service"));
    }

    @Test
    void requestWithoutKeyGets401InProblemFormat() {
        given().get("/api/v1/endpoints")
                .then()
                .statusCode(401)
                .header("WWW-Authenticate", "Bearer")
                .contentType("application/problem+json")
                .body("title", equalTo("Unauthorized"))
                .body("status", equalTo(401));
    }

    @Test
    void unknownKeyGets401() {
        as("whk_this-key-does-not-exist").get("/api/v1/endpoints").then().statusCode(401);
        as("not-even-the-right-format").get("/api/v1/endpoints").then().statusCode(401);
    }

    @Test
    void applicationKeyCannotUseTheAdminApi() {
        String apiKey = createApplication("billing");

        as(apiKey).get("/api/v1/admin/applications").then().statusCode(403).body("title", equalTo("Forbidden"));
    }

    @Test
    void adminKeyIsNotAnApplicationKey() {
        asAdmin().get("/api/v1/endpoints").then().statusCode(403);
    }

    @Test
    void metricsNeedTheAdminKey() {
        String apiKey = createApplication("billing");

        as(apiKey).get("/actuator/prometheus").then().statusCode(403);
        asAdmin().get("/actuator/prometheus").then().statusCode(200).body(containsString("webhook_deliveries_pending"));
    }
}
