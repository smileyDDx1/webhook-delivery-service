package com.debangshu.webhooks.benchmark;

import com.debangshu.webhooks.IntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The worker runs its "what is due?" query every 500 ms, and over time almost every row in deliveries
 * is SUCCEEDED. This fills the table with 1,000,000 deliveries (1% PENDING, about half of those due)
 * and times that query with different indexes:
 *
 * - no index for it at all
 * - my first version: partial index on (next_attempt_at) WHERE status = 'PENDING'  (V1)
 * - the current one: partial index on (status, next_attempt_at) WHERE status = 'PENDING'  (V2)
 * - a normal full index on (status, next_attempt_at), to compare the size
 *
 * Run with: ./mvnw verify -Pbenchmark
 */
@Tag("benchmark")
class ClaimQueryBenchmarkIT extends IntegrationTest {

    private static final String DUE_QUERY = """
            SELECT id FROM deliveries
            WHERE status = 'PENDING' AND next_attempt_at <= now()
            ORDER BY next_attempt_at
            LIMIT 100
            """;
    private static final Pattern EXECUTION_TIME = Pattern.compile("Execution Time: ([0-9.]+) ms");

    record Result(String index, double medianMs, String size, String plan) {
    }

    @Test
    void dueQueryWithDifferentIndexes() {
        worker.pause();
        fillTable();
        long total = jdbc.sql("SELECT count(*) FROM deliveries").query(Long.class).single();
        long pending = jdbc.sql("SELECT count(*) FROM deliveries WHERE status = 'PENDING'").query(Long.class).single();
        long due = jdbc.sql("SELECT count(*) FROM deliveries WHERE status = 'PENDING' AND next_attempt_at <= now()")
                .query(Long.class).single();

        List<Result> results = new ArrayList<>();
        // the table starts with the V2 index
        results.add(measure("partial (status, next_attempt_at) WHERE status = 'PENDING' (current, V2)", "idx_deliveries_due"));

        replaceDueIndex("CREATE INDEX idx_deliveries_due ON deliveries (next_attempt_at) WHERE status = 'PENDING'");
        results.add(measure("partial (next_attempt_at) WHERE status = 'PENDING' (first version, V1)", "idx_deliveries_due"));

        replaceDueIndex("CREATE INDEX idx_deliveries_due ON deliveries (status, next_attempt_at)");
        results.add(measure("full (status, next_attempt_at), not partial", "idx_deliveries_due"));

        replaceDueIndex(null);
        results.add(measure("no index for this query", null));

        // put the schema back the way the migrations made it, then clean up
        replaceDueIndex("CREATE INDEX idx_deliveries_due ON deliveries (status, next_attempt_at) WHERE status = 'PENDING'");
        jdbc.sql("TRUNCATE delivery_attempts, deliveries, events, endpoints, applications CASCADE").update();

        StringBuilder report = new StringBuilder("""

                ### "What is due?" query: %,d deliveries, %,d PENDING, %,d of them due

                | Index | Median execution time (5 runs) | Index size |
                |---|---|---|
                """.formatted(total, pending, due));
        for (Result result : results) {
            report.append("| %s | %s ms | %s |%n".formatted(result.index(), format(result.medianMs()), result.size()));
        }
        for (Result result : results) {
            report.append("%nPlan, %s:%n```%n%s%n```%n".formatted(result.index(), result.plan()));
        }
        BenchmarkReport.write(report.toString());
    }

    /** 100 endpoints x 10,000 events = 1,000,000 deliveries. 99% SUCCEEDED, 1% PENDING spread over +/- 30 minutes. */
    private void fillTable() {
        String apiKey = createApplication("benchmark");
        createEndpoint(apiKey, "/unused");
        long applicationId = jdbc.sql("SELECT id FROM applications").query(Long.class).single();
        jdbc.sql("""
                        INSERT INTO endpoints (application_id, url, event_types, secret, status, consecutive_failures, created_at, updated_at)
                        SELECT :app, 'https://hooks.example.com/' || n, '{}', 'whsec_x', 'ACTIVE', 0, now(), now()
                        FROM generate_series(1, 99) AS n
                        """).param("app", applicationId).update();
        jdbc.sql("""
                        INSERT INTO events (application_id, event_type, payload, created_at)
                        SELECT :app, 'benchmark.test', '{}', now() FROM generate_series(1, 10000)
                        """).param("app", applicationId).update();
        jdbc.sql("""
                        INSERT INTO deliveries (event_id, endpoint_id, application_id, status, attempt_count,
                                                next_attempt_at, version, created_at, updated_at)
                        SELECT ev.id, en.id, :app,
                               CASE WHEN random() < 0.01 THEN 'PENDING' ELSE 'SUCCEEDED' END,
                               1, now() + (random() - 0.5) * interval '1 hour', 0, now(), now()
                        FROM events ev CROSS JOIN endpoints en
                        """).param("app", applicationId).update();
        // like in the real table: finished deliveries have no next attempt
        jdbc.sql("UPDATE deliveries SET next_attempt_at = NULL WHERE status = 'SUCCEEDED'").update();
        jdbc.sql("VACUUM ANALYZE deliveries").update();
    }

    private void replaceDueIndex(String createStatement) {
        jdbc.sql("DROP INDEX IF EXISTS idx_deliveries_due").update();
        if (createStatement != null) {
            jdbc.sql(createStatement).update();
        }
        jdbc.sql("ANALYZE deliveries").update();
    }

    private Result measure(String label, String indexName) {
        List<Double> times = new ArrayList<>();
        for (int run = 0; run < 6; run++) {
            String output = String.join("\n", jdbc.sql("EXPLAIN ANALYZE " + DUE_QUERY).query(String.class).list());
            Matcher matcher = EXECUTION_TIME.matcher(output);
            if (matcher.find() && run > 0) { // the first run only warms the cache
                times.add(Double.parseDouble(matcher.group(1)));
            }
        }
        times.sort(Double::compare);
        String size = indexName == null ? "-"
                : jdbc.sql("SELECT pg_size_pretty(pg_relation_size(:name::regclass))").param("name", indexName)
                .query(String.class).single();
        String plan = String.join("\n", jdbc.sql("EXPLAIN " + DUE_QUERY).query(String.class).list());
        return new Result(label, times.get(times.size() / 2), size, plan);
    }

    private static String format(double ms) {
        return String.format(Locale.ROOT, "%.2f", ms);
    }
}
