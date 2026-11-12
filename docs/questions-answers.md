# Questions & Answers (why I built it this way)

Notes I wrote while building the project, mostly so I can explain my choices in interviews.

## What problem does this solve?

When something happens in a system (an invoice is paid, a customer signs up), other systems often need to know. A webhook is an HTTP POST to a URL the other system gave you. Sending one request is easy. Sending it **reliably** is the hard part:

- the receiver is down for 20 minutes, so the request has to be retried later, without hammering it
- the receiver must be able to check that the request really came from you, and wasn't changed or replayed
- the producer must be able to retry "publish" without creating the same event twice
- someone has to be able to see what was sent, what failed and why, and send it again

Stripe, GitHub, Shopify and Razorpay all run a service like this internally. This project is a small version of one.

## Why Java 21 and Spring Boot 4?

- Java 21 is the LTS most companies use for new projects. It has **virtual threads**, which fit this workload almost perfectly (see below), plus records, pattern matching `switch` and text blocks, which I use a lot.
- Spring Boot 4.1 was the current version when I started (November 2026). The 3.5 line stopped getting free updates in June 2026, so starting a new project on it didn't make sense.
- Spring Boot 4 uses Jackson 3 (package `tools.jackson` instead of `com.fasterxml.jackson`), which tripped me up at first.

## Why PostgreSQL as the queue? Why not Kafka or RabbitMQ?

The `deliveries` table **is** the queue. The worker asks Postgres "give me up to 100 PENDING rows that are due" and marks them SENDING in the same statement.

- **One less system to run.** Postgres is already needed for the data, the API and the delivery log.
- **Atomic with the data.** Publishing an event inserts the event and its deliveries in one transaction. With a separate broker I would need the "transactional outbox" pattern anyway, because "save to DB, then send to Kafka" can fail in between and lose the event.
- **Retries at a specific time are easy:** `next_attempt_at <= now()`. In Kafka, "retry this message in 20 minutes" needs extra retry topics.
- **It's enough at this size.** Thousands of deliveries per second are fine for Postgres. At much higher volume a broker makes sense, see [future-plans.md](future-plans.md).

## What does `FOR UPDATE SKIP LOCKED` do?

`FOR UPDATE` locks the rows a query selects. Normally a second worker selecting the same rows would **wait** for the lock. `SKIP LOCKED` makes it **skip** locked rows and take the next free ones instead. So 8 threads (or 3 app instances) can claim work at the same moment and each delivery is claimed exactly once, with no waiting.

`ConcurrencyIT.eightWorkersClaimingAtOnceNeverGetTheSameDelivery` checks this with 8 threads and 2,000 deliveries.

## At-least-once vs exactly-once

Delivery is **at least once**. If a worker sends a request and then crashes before it saves the result, the lease reaper puts the delivery back in the queue and it is sent again. The receiver may already have processed the first one.

Exactly-once delivery over HTTP isn't possible in general (the network can fail after the receiver processed the request but before we see its answer). The usual fix is to make the receiver **idempotent**: every request has a `webhook-id` that stays the same on every retry, so the receiver can store the ids it has seen and ignore duplicates.

## Why is the queue index `(status, next_attempt_at) WHERE status = 'PENDING'`?

The worker asks "which PENDING deliveries are due?" every 500 ms, and after a while 99% of the table is SUCCEEDED. A **partial index** only contains the rows that match its `WHERE`, so it stays tiny no matter how big the table gets.

My first version (in `V1`) was `(next_attempt_at) WHERE status = 'PENDING'`. The benchmark showed it was slower than I expected, and `EXPLAIN ANALYZE` showed why: Postgres estimated about 50 due rows when there were about 5,000. It treats `status = 'PENDING'` and `next_attempt_at <= now()` as independent and multiplies their selectivities (1% x 1%), but here they aren't independent: only PENDING rows have a `next_attempt_at`. Expecting 50 rows, it chose a bitmap scan that reads **every** due row and sorts them, instead of walking the index in order and stopping after the 100 rows the worker asked for.

With `status` as the first column (`V2`), the planner picks the ordered index scan, which stops after `LIMIT` rows. Extended statistics (`CREATE STATISTICS`) didn't change the plan. A normal full index on `(status, next_attempt_at)` is just as fast but over 20 times bigger, because it also indexes the 99% finished rows. The numbers are in [benchmark-results.md](benchmark-results.md).

I added `V2` instead of editing `V1`, because Flyway stores a checksum of every applied migration and refuses to start if an old one changes.

## What is the lease, and why is there a reaper?

When the worker claims a delivery it sets `locked_until = now + 60s` (the lease). If the worker dies (crash, `kill -9`, deploy), the delivery would stay SENDING forever. The reaper runs every 30 seconds and puts SENDING rows with an expired lease back to PENDING, and writes a `LEASE_EXPIRED` attempt so the gap shows up in the delivery log.

## Optimistic and pessimistic locking, both?

- **Pessimistic** (`FOR UPDATE SKIP LOCKED`) for claiming. Many workers compete for the same rows all the time, so taking a lock up front is the right tool.
- **Optimistic** (`@Version` on `Delivery`) for saving the result. Conflicts are rare (only when the reaper took the delivery back while a slow request was still running). Hibernate adds `WHERE version = ?` to the UPDATE; if the reaper changed the row, the update matches 0 rows and my late result is thrown away instead of overwriting the newer state.

## Why are some queries JPA and some plain SQL (JdbcClient)?

- **JPA (Spring Data)** for normal CRUD: applications, endpoints, reading deliveries. Less code, and Specifications for the optional list filters.
- **JdbcClient (plain SQL)** where I need Postgres features JPQL doesn't have: `SKIP LOCKED`, `UPDATE ... RETURNING`, `INSERT ... ON CONFLICT`, data-modifying CTEs, `percentile_cont`. And for the endpoint failure counter, which has to be an atomic `SET consecutive_failures = consecutive_failures + 1`. Loading the entity, adding 1 in Java and saving would lose updates when 40 threads do it at once (`ConcurrencyIT.failureCounterLosesNoUpdatesUnderConcurrency`).

Both use the same transaction (Spring's `JpaTransactionManager` shares the JDBC connection with `JdbcClient`).

## How do idempotency keys work here?

The producer sends `Idempotency-Key: invoice-42-paid`. The `events` table has `UNIQUE (application_id, idempotency_key)`.

My first version was "SELECT by key, INSERT if not found". Two requests at the same moment can both see "not found" and both insert, which is exactly the duplicate the key should prevent. The final version uses `INSERT ... ON CONFLICT DO NOTHING RETURNING id`: Postgres makes the second insert wait for the first to commit, then it inserts nothing, and I return the existing event. `EventApiIT.concurrentRequestsWithTheSameKeyCreateOneEvent` sends 10 requests at once and checks that only one event exists.

Same key with a **different** payload returns 409, because that is almost certainly a bug on the producer's side.

## Why HMAC signatures, and what does the timestamp do?

Anyone who knows the receiver's URL can POST to it. The receiver needs proof the request came from us. Every endpoint has a secret only we and the receiver know. We send `HMAC-SHA256(secret, id + "." + timestamp + "." + body)`. The receiver computes the same thing and compares.

- If the body was changed, the HMAC doesn't match.
- The **timestamp is inside the signed content**, and the receiver rejects timestamps older than 5 minutes. So an attacker who recorded a valid request can't send it again tomorrow (replay attack), and can't change the timestamp without breaking the signature.
- The comparison uses `MessageDigest.isEqual`, which takes the same time whether the first or the last character differs. `String.equals` returns at the first difference, and that timing difference can leak the signature.

I used the [Standard Webhooks](https://www.standardwebhooks.com) format so receivers can verify with existing libraries. `WebhookSignatureTest` checks the spec's own test vector.

## How does secret rotation work without downtime?

After a rotation the old secret keeps signing for 24 hours. During that time every request has **two** signatures in the `webhook-signature` header (space separated), and the receiver accepts the request if either one matches. So the receiver can switch to the new secret whenever it's ready.

## Why exponential backoff with jitter?

- **Exponential:** a receiver that is down for a minute should get a quick retry, one that is down for hours shouldn't get a request every 5 seconds. Delays grow 4x: 5s, 20s, 1m20s, 5m20s, 21m, 1h25m, 4h.
- **Jitter (+/- 20%):** if an outage makes 10,000 deliveries fail in the same second, without jitter all 10,000 retries come back in the same second too, and knock the receiver over again right when it recovers ("thundering herd").
- **Retry-After:** if the receiver answers 429 or 503 with `Retry-After`, that wins over the backoff (capped at 1 hour).
- **410 Gone** means "this URL doesn't exist any more", so the endpoint is disabled instead of retried.

## What does the circuit breaker add on top of retries?

Retries are per delivery. If an endpoint is down and has 5,000 deliveries queued, each of them would keep trying on its own schedule: thousands of requests that will all fail. The breaker counts failures **per endpoint**. After 10 failures in a row it pauses the endpoint for 5 minutes: its waiting deliveries are moved to the end of the pause and don't use up their retry attempts. After the pause, the next delivery is the test. Success closes the breaker, another failure pauses it again.

It's not perfectly strict: requests that were already in flight when the breaker opened still finish. That's normal for circuit breakers.

## Why virtual threads?

A webhook request spends almost all of its time **waiting** for the receiver. With platform threads, 200 requests in flight need 200 OS threads (about 1 MB of stack each). A virtual thread that is blocked on I/O is parked by the JVM and costs a few KB, so the code can stay simple and blocking (`HttpClient.send`) without needing hundreds of OS threads. The benchmark shows the difference in thread count ([benchmark-results.md](benchmark-results.md)).

A `Semaphore` still limits how many requests are in flight, because "unlimited virtual threads" would mean 50,000 open connections after an outage.

On Java 21, a virtual thread inside a `synchronized` block "pins" its carrier thread. I only use `synchronized` for tiny in-memory sections (the token bucket, worker start/stop). Java 24 removed this pinning (JEP 491).

## Why not hold the transaction open while sending?

The worker loads the delivery (short read transaction), sends the request **with no transaction and no DB connection**, then saves the result (short write transaction). If the transaction stayed open, a receiver that takes 10 seconds to answer would keep a pooled connection busy for 10 seconds, and 20 slow receivers would use up the whole pool.

## Why no `@Async` or `@Scheduled`?

I wanted explicit control: a single poller thread, a separate executor for sending (virtual or platform threads, chosen by config), a semaphore for back-pressure, and a clean shutdown order. `SmartLifecycle` lets Spring start the worker after everything else is ready and stop it first, before the connection pool closes.

## SSRF: why can't I register `http://localhost:5432`?

This service makes HTTP requests to URLs that users type in. Without a check, someone could register `http://169.254.169.254/latest/meta-data/` (the cloud metadata service, which can hand out credentials) or an internal admin page, and make **our** server call it. So endpoint URLs that point to loopback, private, link-local or other internal addresses are rejected, and redirects are never followed (a redirect could point somewhere internal).

Known gap: the host name is checked when the endpoint is saved. A DNS name could later be changed to point at an internal address (DNS rebinding). The proper fix is to check the IP at connection time, for example with an egress proxy. It's in [future-plans.md](future-plans.md).

## Why SHA-256 for API keys, but you'd use BCrypt for passwords?

Passwords are short and guessable, so they need a slow hash (BCrypt, Argon2) to make brute force expensive. API keys here are 32 random bytes: there is nothing to guess. A fast hash is enough, and it matters because the key is checked on **every** request. Only the hash is stored, so a database leak doesn't leak working keys.

## Why a token bucket for rate limiting?

"At most N requests per second" with a fixed window lets a client send N at 0.999s and N more at 1.001s. A token bucket refills continuously, allows short bursts (up to 2x the rate), and keeps the long-term average at the limit. The limit is per application and lives in memory, so with 3 instances the real limit is 3x. A shared limit would need Redis.

## Why Testcontainers instead of H2?

The code depends on Postgres-only features (`SKIP LOCKED`, `ON CONFLICT`, `text[]`, `percentile_cont`, partial indexes). H2 doesn't support most of them, so tests on H2 would either fail or test something different from production. Testcontainers starts a real `postgres:16-alpine` in Docker, Flyway runs the real migrations, and the tests talk to the same database as production.

## Why WireMock and Rest Assured?

- **WireMock** plays the receiver: I can make it return 500 twice and then 200 (scenarios), answer slowly, return `Retry-After`, and afterwards look at exactly what it received (headers, body) to verify the signature like a real receiver would.
- **Rest Assured** for calling my own API in integration tests. It reads well (`given().when().then()`), and it's also the tool most Java QA / SDET teams use for API testing.

## Why no Lombok?

Records cover all my DTOs, and the entities have plain getters. I wanted the code to be readable without knowing what an annotation generates, and one less annotation processor to configure.

## Why `TEXT` for the payload and not `JSONB`?

I never query inside payloads, and the exact text is what gets delivered and signed. `JSONB` reorders keys and removes whitespace, which is fine but not needed here. If I wanted "find all events where payload.customerId = X", JSONB with a GIN index would be the right choice.
