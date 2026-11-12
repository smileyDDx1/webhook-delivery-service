-- Applications publish events and own endpoints. Each one has its own API key.
CREATE TABLE applications (
    id                    BIGSERIAL    PRIMARY KEY,
    name                  VARCHAR(100) NOT NULL,
    api_key_prefix        VARCHAR(16)  NOT NULL,
    api_key_hash          VARCHAR(64)  NOT NULL,
    rate_limit_per_second INTEGER      NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_applications_api_key_hash UNIQUE (api_key_hash)
);

-- URLs that receive webhooks
CREATE TABLE endpoints (
    id                         BIGSERIAL     PRIMARY KEY,
    application_id             BIGINT        NOT NULL REFERENCES applications (id) ON DELETE CASCADE,
    url                        VARCHAR(2048) NOT NULL,
    description                VARCHAR(255),
    event_types                TEXT[]        NOT NULL DEFAULT '{}',
    secret                     VARCHAR(100)  NOT NULL,
    previous_secret            VARCHAR(100),
    previous_secret_expires_at TIMESTAMPTZ,
    status                     VARCHAR(20)   NOT NULL,
    consecutive_failures       INTEGER       NOT NULL DEFAULT 0,
    paused_until               TIMESTAMPTZ,
    disabled_reason            VARCHAR(255),
    created_at                 TIMESTAMPTZ   NOT NULL,
    updated_at                 TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_endpoints_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);
CREATE INDEX idx_endpoints_application ON endpoints (application_id);

CREATE TABLE events (
    id              BIGSERIAL    PRIMARY KEY,
    application_id  BIGINT       NOT NULL REFERENCES applications (id) ON DELETE CASCADE,
    event_type      VARCHAR(100) NOT NULL,
    payload         TEXT         NOT NULL,
    idempotency_key VARCHAR(255),
    created_at      TIMESTAMPTZ  NOT NULL,
    -- NULLs never clash in a UNIQUE constraint, so events without a key are always accepted
    CONSTRAINT uq_events_idempotency_key UNIQUE (application_id, idempotency_key)
);
CREATE INDEX idx_events_application_created ON events (application_id, created_at DESC);

-- One row per (event, endpoint). This table is also the work queue for the delivery worker.
CREATE TABLE deliveries (
    id                   BIGSERIAL   PRIMARY KEY,
    event_id             BIGINT      NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    endpoint_id          BIGINT      NOT NULL REFERENCES endpoints (id) ON DELETE CASCADE,
    application_id       BIGINT      NOT NULL, -- copied from the event, saves a join in list queries
    status               VARCHAR(20) NOT NULL,
    attempt_count        INTEGER     NOT NULL DEFAULT 0,
    next_attempt_at      TIMESTAMPTZ,
    locked_until         TIMESTAMPTZ,
    last_attempt_at      TIMESTAMPTZ,
    last_response_status INTEGER,
    last_error           VARCHAR(500),
    version              BIGINT      NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL,
    updated_at           TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_deliveries_event_endpoint UNIQUE (event_id, endpoint_id),
    CONSTRAINT ck_deliveries_status CHECK (status IN ('PENDING', 'SENDING', 'SUCCEEDED', 'FAILED'))
);
-- The worker asks "which PENDING deliveries are due?" every poll. Almost all rows end up SUCCEEDED,
-- so a partial index that only contains PENDING rows stays small and fast no matter how big the table gets.
-- (docs/benchmark-results.md has the before/after on 1 million rows)
CREATE INDEX idx_deliveries_due ON deliveries (next_attempt_at) WHERE status = 'PENDING';
-- the lease reaper looks for SENDING rows whose lease ran out
CREATE INDEX idx_deliveries_sending ON deliveries (locked_until) WHERE status = 'SENDING';
CREATE INDEX idx_deliveries_endpoint_status ON deliveries (endpoint_id, status);
CREATE INDEX idx_deliveries_application ON deliveries (application_id, id DESC);

-- The delivery log: one row per HTTP request
CREATE TABLE delivery_attempts (
    id              BIGSERIAL     PRIMARY KEY,
    delivery_id     BIGINT        NOT NULL REFERENCES deliveries (id) ON DELETE CASCADE,
    endpoint_id     BIGINT        NOT NULL,
    started_at      TIMESTAMPTZ   NOT NULL,
    duration_ms     INTEGER       NOT NULL,
    response_status INTEGER,
    response_body   VARCHAR(1000),
    error           VARCHAR(500),
    outcome         VARCHAR(20)   NOT NULL
);
CREATE INDEX idx_attempts_delivery ON delivery_attempts (delivery_id);
CREATE INDEX idx_attempts_endpoint_started ON delivery_attempts (endpoint_id, started_at);
