-- The first version of idx_deliveries_due (in V1) was (next_attempt_at) WHERE status = 'PENDING'.
--
-- The benchmark showed it wasn't used the way I expected. EXPLAIN ANALYZE: Postgres estimated ~50 due
-- rows when there were ~5,000. It treats "status = 'PENDING'" and "next_attempt_at <= now()" as
-- independent, but in this table only PENDING rows have a next_attempt_at, so it multiplied two 1%
-- selectivities. With so few rows expected it chose a bitmap scan that reads EVERY due row and then sorts
-- them, instead of reading the index in next_attempt_at order and stopping after the 100 the worker needs.
--
-- With status as the first column, the planner picks the ordered index scan and stops after LIMIT rows.
-- The index is still partial, so it only contains PENDING rows and stays tiny.
-- Numbers (1 million deliveries) are in docs/benchmark-results.md.
--
-- A new migration instead of editing V1: V1 has already run on every existing database, and Flyway
-- refuses to start when an applied migration's checksum changes.
DROP INDEX idx_deliveries_due;
CREATE INDEX idx_deliveries_due ON deliveries (status, next_attempt_at) WHERE status = 'PENDING';
