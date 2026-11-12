import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A pretend customer server for the demo, using only the JDK (no Spring, no libraries).
 *
 *   POST /ok              -> 200 always
 *   POST /flaky?fail=0.5  -> 500 for 50% of requests (default 0.3)
 *   POST /slow?ms=3000    -> 200 after a delay
 *   POST /down            -> 503 always
 *   POST /gone            -> 410 always (the service disables the endpoint)
 *   POST /busy            -> 429 with Retry-After: 5
 *   GET  /stats           -> how many requests each path got, and how they were answered
 *
 * Every request is printed, so `docker compose logs -f demo-receiver` shows retries happening.
 */
public class DemoReceiver {

    private static final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "9090"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 200);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", DemoReceiver::handle);
        server.start();
        System.out.println("Demo receiver listening on port " + port);
    }

    private static void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            URI uri = exchange.getRequestURI();
            String path = uri.getPath();
            if (exchange.getRequestMethod().equals("GET") && path.equals("/stats")) {
                send(exchange, 200, stats());
                return;
            }
            exchange.getRequestBody().readAllBytes();
            int status = switch (path) {
                case "/ok" -> 200;
                case "/flaky" -> ThreadLocalRandom.current().nextDouble() < param(uri, "fail", 0.3) ? 500 : 200;
                case "/slow" -> {
                    sleep((long) param(uri, "ms", 3000));
                    yield 200;
                }
                case "/down" -> 503;
                case "/gone" -> 410;
                case "/busy" -> {
                    exchange.getResponseHeaders().add("Retry-After", "5");
                    yield 429;
                }
                default -> 404;
            };
            counts.computeIfAbsent(path + " -> " + status, key -> new AtomicInteger()).incrementAndGet();
            System.out.printf("%s %s %s webhook-id=%s -> %d%n", LocalTime.now().truncatedTo(ChronoUnit.MILLIS),
                    exchange.getRequestMethod(), path, exchange.getRequestHeaders().getFirst("webhook-id"), status);
            send(exchange, status, status < 300 ? "{\"received\":true}" : "{\"error\":\"demo failure\"}");
        }
    }

    private static String stats() {
        StringBuilder json = new StringBuilder("{");
        new TreeMap<>(counts).forEach((key, value) -> {
            if (json.length() > 1) {
                json.append(',');
            }
            json.append('"').append(key).append("\":").append(value.get());
        });
        return json.append('}').toString();
    }

    private static double param(URI uri, String name, double defaultValue) {
        String query = uri.getQuery();
        if (query == null) {
            return defaultValue;
        }
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2 && parts[0].equals(name)) {
                try {
                    return Double.parseDouble(parts[1]);
                } catch (NumberFormatException ignored) {
                    return defaultValue;
                }
            }
        }
        return defaultValue;
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
