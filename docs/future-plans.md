# Future plans

Things I want to add next, roughly in order.

1. **Retention job.** `events`, `deliveries` and `delivery_attempts` grow forever. A nightly job should delete finished deliveries and attempts older than N days (in batches, so it doesn't lock the table), or the tables could be partitioned by month and old partitions dropped.
2. **Check the IP at connection time (SSRF).** The URL check runs when the endpoint is saved. A DNS name can be changed later to point inside the network (DNS rebinding). Sending through an egress proxy that blocks private ranges (like Stripe's Smokescreen) would close that gap.
3. **Shared rate limits.** The token buckets are in memory, so 3 instances give an application 3x its limit. Redis (one bucket per application, updated with a Lua script) would make the limit global.
4. **Per-endpoint ordering (optional).** Right now deliveries to the same endpoint can arrive out of order (event 2 can succeed before a retry of event 1). Some receivers need order. That could be an endpoint setting: "only one delivery in flight per endpoint, oldest first".
5. **Auto-disable after a long outage.** The circuit breaker pauses an endpoint again and again. After for example 3 days of only failures it should disable the endpoint and notify the owner.
6. **Event type catalogue with JSON Schema.** Applications could register their event types with a schema, and publishing would validate the payload against it.
7. **Cache API key lookups.** Every request does one indexed query for the key hash. A short-lived Caffeine cache would remove most of them (it has to be cleared when a key is rotated).
8. **Public ids.** The API exposes database ids (`evt_42`). Random ids (UUIDv7 or ULID) would hide how many events other applications create.
9. **Dashboard.** A small UI for the delivery log: filter by status, look at attempts, retry with one click.
10. **Kafka as an optional queue.** For much higher volume, the outbox rows could be streamed into Kafka (Debezium) and consumed by a separate delivery service that scales on its own.
11. **Java 25.** The next LTS removes virtual thread pinning on `synchronized` (JEP 491) and has better startup with AOT caching.
