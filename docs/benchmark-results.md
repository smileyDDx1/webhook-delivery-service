# Benchmark results

Measured on 13 November 2026 on my laptop: Intel Core i3-1115G4 (2 cores / 4 threads), 7.7 GB RAM, Windows 11, Docker Desktop (WSL 2) limited to 4 CPUs and 3.9 GB. The app, PostgreSQL 16 and the test receiver all ran inside Docker on that machine. These numbers are for comparing settings with each other on the same machine, not what a production server would do.

Run them yourself with `./mvnw verify -Pbenchmark` (needs Docker). Each run also writes its tables to `target/benchmark-results.md`.

## 1. The queue query and its index

The worker runs this every 500 ms:

```sql
SELECT id FROM deliveries
WHERE status = 'PENDING' AND next_attempt_at <= now()
ORDER BY next_attempt_at
LIMIT 100
```

Setup ([ClaimQueryBenchmarkIT](../src/test/java/com/debangshu/webhooks/benchmark/ClaimQueryBenchmarkIT.java)): **1,000,000 deliveries**, 99% SUCCEEDED (like a table that has been running for a while), 9,918 PENDING, 5,275 of them due. Median of 5 runs of `EXPLAIN ANALYZE` after one warm-up run.

| Index | Median execution time | Index size |
|---|---|---|
| no index for this query | 13.02 ms | - |
| partial `(next_attempt_at) WHERE status = 'PENDING'` (my first version, V1) | 11.71 ms | 240 kB |
| **partial `(status, next_attempt_at) WHERE status = 'PENDING'` (current, V2)** | **0.18 ms** | **400 kB** |
| full `(status, next_attempt_at)`, not partial | 0.18 ms | 7,200 kB |

**What happened.** My first partial index barely helped (11.7 ms vs 13.0 ms with no index). The plan shows why:

```
V1:  Limit -> Sort -> Bitmap Heap Scan (estimated rows=56) -> Bitmap Index Scan on idx_deliveries_due
V2:  Limit -> Index Scan using idx_deliveries_due (stops after 100 rows)
```

Postgres estimated 56 due rows when there were 5,275. It assumes `status = 'PENDING'` and `next_attempt_at <= now()` are independent and multiplies their selectivities, but only PENDING rows have a `next_attempt_at`. Expecting 56 rows, a bitmap scan plus a sort looks cheap, so it read all 5,275 due rows and sorted them to return 100. With `status` as the leading column (V2) the planner walks the index in order and stops after the `LIMIT`: **65x faster**. The full index is just as fast but **18x bigger**, because it also indexes the 990,000 finished rows (and has to be updated every time one of them changes).

I also tried `CREATE STATISTICS ... (mcv) ON status, next_attempt_at` in a separate experiment; it didn't change the plan. The full plans are in `target/benchmark-results.md` after a run.

The fix is a new migration ([V2](../src/main/resources/db/migration/V2__faster_due_deliveries_index.sql)) instead of an edit to V1, because Flyway refuses to start when an already-applied migration changes.

## 2. Delivery throughput

Setup ([DeliveryThroughputBenchmarkIT](../src/test/java/com/debangshu/webhooks/benchmark/DeliveryThroughputBenchmarkIT.java)): the real worker and the real database, sending to a small HTTP server inside the test JVM that waits **100 ms** before it answers `204`. The deliveries are inserted as due, and the clock runs from starting the worker until all of them are SUCCEEDED. Every delivery is a claim (batched), one query to load it, the HTTP request, and a short transaction to save the result.

Three runs, all with the V2 index. (One earlier run, made while V1 was still the index, is left out.)

| Threads | Max in flight | Deliveries | Deliveries/s (run 1 / 2 / 3) | Median | Peak JVM threads (median) |
|---|---|---|---|---|---|
| platform | 1 | 100 | 6 / 6 / 6 | **6** | 48 |
| platform | 10 | 1,000 | 17 / 57 / 45 | **45** | 63 |
| platform | 50 | 2,000 | 60 / 80 / 85 | **80** | 114 |
| platform | 200 | 5,000 | 101 / 123 / 150 | **123** | 274 |
| virtual | 200 | 5,000 | 219 / 78 / 94 | **94** | 80 |

What the numbers show, and what they don't:

- **Sending in parallel is what matters most.** One request at a time manages 6 deliveries per second (each one takes about 170 ms: the receiver's 100 ms plus the database work and the poll interval). With 50 in flight it's about 80 per second, 13 times more.
- **Above about 50 in flight, this laptop is the limit, not the worker.** PostgreSQL, the app and the receiver share 2 physical cores, so the same code at 200 in flight did anywhere from 78 to 219 deliveries per second depending on the run. The differences between the 200-in-flight rows are smaller than that noise, so I don't read anything into them.
- **Virtual threads didn't make it faster here, they made it lighter.** At 200 requests in flight, the platform thread pool needed about 275 JVM threads and virtual threads about 80 (most of those 80 belong to the JVM, Tomcat, the connection pool and Testcontainers, not to delivery). That difference was the same in every run. On a machine with spare CPU, the next limit would be the database connection pool (20 connections) rather than threads.
