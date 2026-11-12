package com.debangshu.webhooks.sending;

import com.debangshu.webhooks.config.AppProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

/**
 * Sends one webhook with the JDK's built-in HttpClient (java.net.http). It is a blocking call, which is
 * fine because every delivery runs on its own virtual thread (see DeliveryWorker).
 *
 * Never throws: every outcome (2xx, 500, timeout, connection refused...) comes back as a SendResult.
 */
@Component
public class WebhookHttpSender {

    static final int RESPONSE_BODY_LIMIT = 1000;
    private static final String USER_AGENT = "webhook-delivery-service/0.1";

    private final HttpClient client;
    private final Duration timeout;
    private final Clock clock;

    @Autowired
    public WebhookHttpSender(AppProperties properties, Clock clock) {
        this(properties.delivery().httpTimeout(), clock);
    }

    public WebhookHttpSender(Duration timeout, Clock clock) {
        this.timeout = timeout;
        this.clock = clock;
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                // a redirect could point to an internal address and get around the SSRF check, so 3xx is
                // treated like any other non-2xx answer instead of being followed
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public SendResult send(String url, Map<String, String> headers, String body) {
        long start = System.nanoTime();
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            headers.forEach(request::header);

            // ofInputStream + readNBytes: I only keep the first 1000 bytes of the answer. ofString() would
            // read the whole body into memory, even if a broken receiver sends back 500 MB.
            HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            String responseBody;
            try (InputStream in = response.body()) {
                responseBody = new String(in.readNBytes(RESPONSE_BODY_LIMIT), StandardCharsets.UTF_8);
            }
            Duration retryAfter = RetryAfterParser.parse(response.headers().firstValue("Retry-After").orElse(null), clock.instant());
            return SendResult.response(response.statusCode(), responseBody, retryAfter, elapsedMs(start));
        } catch (HttpTimeoutException e) {
            return SendResult.timeout("No response within " + timeout.toMillis() + " ms", elapsedMs(start));
        } catch (IOException e) {
            return SendResult.networkError(describe(e), elapsedMs(start));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SendResult.networkError("Interrupted while sending (shutting down)", elapsedMs(start));
        } catch (IllegalArgumentException e) {
            return SendResult.networkError("Invalid URL: " + e.getMessage(), elapsedMs(start));
        }
    }

    private static int elapsedMs(long startNanos) {
        return (int) ((System.nanoTime() - startNanos) / 1_000_000);
    }

    private static String describe(IOException e) {
        // e.g. "ConnectException: Connection refused". Some JDK exceptions have no message, the class name still helps.
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
