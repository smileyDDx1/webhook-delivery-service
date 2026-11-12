package com.debangshu.webhooks;

import com.debangshu.webhooks.delivery.DeliveryWorker;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.util.Arrays;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static io.restassured.RestAssured.given;

/**
 * Base class for the integration tests: the whole app on a random port, a real Postgres (Testcontainers)
 * and a fake receiver (WireMock) that the webhooks are sent to.
 *
 * All test classes use exactly the same configuration on purpose, so Spring builds the context once
 * and reuses it. (Two contexts would also mean two delivery workers polling the same database.)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

    protected static final String ADMIN_KEY = "test-admin-key";

    protected static final WireMockServer receiver = new WireMockServer(options().dynamicPort());

    static {
        receiver.start(); // stays up for the whole test run
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected DeliveryWorker worker;

    @BeforeEach
    void resetState() {
        RestAssured.port = port;
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
        worker.resume();
        receiver.resetAll();
        // no RESTART IDENTITY: new ids in every test, so in-memory state keyed by id (rate limit buckets) can't leak
        jdbc.sql("TRUNCATE delivery_attempts, deliveries, events, endpoints, applications CASCADE").update();
    }

    protected RequestSpecification asAdmin() {
        return as(ADMIN_KEY);
    }

    protected RequestSpecification as(String apiKey) {
        return given().header("Authorization", "Bearer " + apiKey).contentType(ContentType.JSON);
    }

    /** Creates an application and returns its API key. */
    protected String createApplication(String name) {
        return createApplication(name, 1000);
    }

    protected String createApplication(String name, int eventsPerSecond) {
        return asAdmin()
                .body("""
                        {"name": "%s", "rateLimitPerSecond": %d}""".formatted(name, eventsPerSecond))
                .post("/api/v1/admin/applications")
                .then().statusCode(201)
                .extract().jsonPath().getString("apiKey");
    }

    protected long createEndpoint(String apiKey, String path, String... eventTypes) {
        String types = Arrays.stream(eventTypes).map(type -> "\"" + type + "\"").collect(Collectors.joining(","));
        return as(apiKey)
                .body("""
                        {"url": "%s", "description": "test", "eventTypes": [%s]}""".formatted(receiverUrl(path), types))
                .post("/api/v1/endpoints")
                .then().statusCode(201)
                .extract().jsonPath().getLong("id");
    }

    protected long publish(String apiKey, String eventType, String payloadJson) {
        return as(apiKey)
                .body("""
                        {"eventType": "%s", "payload": %s}""".formatted(eventType, payloadJson))
                .post("/api/v1/events")
                .then().statusCode(202)
                .extract().jsonPath().getLong("id");
    }

    /** The id of the (only) delivery of an event. */
    protected long deliveryOf(String apiKey, long eventId) {
        return as(apiKey).get("/api/v1/events/{id}", eventId)
                .then().statusCode(200)
                .extract().jsonPath().getLong("deliveries[0].deliveryId");
    }

    protected String deliveryStatus(String apiKey, long deliveryId) {
        return as(apiKey).get("/api/v1/deliveries/{id}", deliveryId)
                .then().statusCode(200)
                .extract().jsonPath().getString("status");
    }

    protected String secretOf(String apiKey, long endpointId) {
        return as(apiKey).get("/api/v1/endpoints/{id}/secret", endpointId)
                .then().statusCode(200)
                .extract().jsonPath().getString("secret");
    }

    protected String receiverUrl(String path) {
        return "http://localhost:" + receiver.port() + path;
    }
}
