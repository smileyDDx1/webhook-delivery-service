package com.debangshu.webhooks.sending;

import com.debangshu.webhooks.delivery.AttemptOutcome;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/** Talks to a real HTTP server (WireMock) but without Spring, so it runs in about a second. */
class WebhookHttpSenderTest {

    private static final WireMockServer receiver = new WireMockServer(options().dynamicPort());
    private static final WebhookHttpSender sender = new WebhookHttpSender(Duration.ofSeconds(1), Clock.systemUTC());

    @BeforeAll
    static void startReceiver() {
        receiver.start();
        // the very first request pays for class loading in the JDK client and in WireMock (it once took
        // more than 500 ms on my laptop and the first test timed out), so warm both up once
        receiver.stubFor(post("/warm-up").willReturn(aResponse().withStatus(200)));
        sender.send("http://localhost:" + receiver.port() + "/warm-up", Map.of(), "{}");
    }

    @AfterAll
    static void stopReceiver() {
        receiver.stop();
    }

    @BeforeEach
    void reset() {
        receiver.resetAll();
    }

    private String url(String path) {
        return "http://localhost:" + receiver.port() + path;
    }

    @Test
    void sendsBodyAndHeadersAndReportsSuccess() {
        receiver.stubFor(post("/in").willReturn(aResponse().withStatus(204)));

        SendResult result = sender.send(url("/in"), Map.of("webhook-id", "evt_1"), "{\"a\":1}");

        assertThat(result.outcome()).isEqualTo(AttemptOutcome.SUCCESS);
        assertThat(result.statusCode()).isEqualTo(204);
        assertThat(result.error()).isNull();
        receiver.verify(postRequestedFor(urlEqualTo("/in"))
                .withHeader("webhook-id", equalTo("evt_1"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson("{\"a\":1}")));
    }

    @Test
    void non2xxIsAnHttpErrorWithTheResponseBody() {
        receiver.stubFor(post("/in").willReturn(aResponse().withStatus(500).withBody("database is down")));

        SendResult result = sender.send(url("/in"), Map.of(), "{}");

        assertThat(result.outcome()).isEqualTo(AttemptOutcome.HTTP_ERROR);
        assertThat(result.statusCode()).isEqualTo(500);
        assertThat(result.responseBody()).isEqualTo("database is down");
        assertThat(result.error()).isEqualTo("Receiver answered HTTP 500");
    }

    @Test
    void onlyTheFirstThousandBytesOfTheResponseAreKept() {
        receiver.stubFor(post("/in").willReturn(aResponse().withStatus(400).withBody("x".repeat(50_000))));

        SendResult result = sender.send(url("/in"), Map.of(), "{}");

        assertThat(result.responseBody()).hasSize(1000);
    }

    @Test
    void slowReceiverIsATimeout() {
        receiver.stubFor(post("/slow").willReturn(aResponse().withStatus(200).withFixedDelay(3_000)));

        SendResult result = sender.send(url("/slow"), Map.of(), "{}");

        assertThat(result.outcome()).isEqualTo(AttemptOutcome.TIMEOUT);
        assertThat(result.statusCode()).isNull();
        assertThat(result.durationMs()).isBetween(950, 2_900);
    }

    @Test
    void closedPortIsANetworkError() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort(); // free a port, nothing listens on it afterwards
        }

        SendResult result = sender.send("http://localhost:" + closedPort + "/in", Map.of(), "{}");

        assertThat(result.outcome()).isEqualTo(AttemptOutcome.NETWORK_ERROR);
        assertThat(result.error()).contains("ConnectException");
    }

    @Test
    void retryAfterHeaderIsRead() {
        receiver.stubFor(post("/in").willReturn(aResponse().withStatus(429).withHeader("Retry-After", "30")));

        SendResult result = sender.send(url("/in"), Map.of(), "{}");

        assertThat(result.retryAfter()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void redirectsAreNotFollowed() {
        receiver.stubFor(post("/old").willReturn(aResponse().withStatus(302).withHeader("Location", url("/new"))));
        receiver.stubFor(post("/new").willReturn(aResponse().withStatus(200)));

        SendResult result = sender.send(url("/old"), Map.of(), "{}");

        assertThat(result.outcome()).isEqualTo(AttemptOutcome.HTTP_ERROR);
        assertThat(result.statusCode()).isEqualTo(302);
        receiver.verify(0, postRequestedFor(urlEqualTo("/new")));
    }
}
