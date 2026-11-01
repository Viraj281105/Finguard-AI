-- V8: Transactional Outbox, Consumer Inbox, Dead-Letter Queue, and Append-Only Audit Log (S1-02).
-- Recovery plan:
--   DROP TABLE IF EXISTS audit_log;
--   DROP TABLE IF EXISTS event_dead_letter;
--   DROP TABLE IF EXISTS processed_event;
--   DROP TABLE IF EXISTS event_outbox;
--   DROP INDEX IF EXISTS idx_transactions_import_job_id;

-- 1. Transactional Outbox (written in the same PostgreSQL transaction as domain mutations)
CREATE TABLE event_outbox (
    id             uuid PRIMARY KEY,
    aggregate_type text        NOT NULL,
    aggregate_id   text        NOT NULL,
    event_type     text        NOT NULL,
    schema_version integer     NOT NULL DEFAULT 1,
    tenant         text        NOT NULL,
    trace_id       text,
    occurred_at    timestamptz NOT NULL DEFAULT now(),
    payload        jsonb       NOT NULL,
    published_at   timestamptz,
    attempts       integer     NOT NULL DEFAULT 0,
    last_error     text,
    CONSTRAINT ck_event_outbox_schema_version CHECK (schema_version >= 1),
    CONSTRAINT ck_event_outbox_attempts CHECK (attempts >= 0)
);

CREATE INDEX ix_event_outbox_unpublished ON event_outbox (occurred_at, id) WHERE published_at IS NULL;
CREATE INDEX ix_event_outbox_tenant_occurred ON event_outbox (tenant, occurred_at DESC);

-- 2. Consumer Inbox (idempotent deduplication per consumer + event_id)
CREATE TABLE processed_event (
    consumer     text        NOT NULL,
    event_id     uuid        NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);

-- 3. Dead-Letter Queue (poison events or events exceeding retry cap)
CREATE TABLE event_dead_letter (
    id           uuid PRIMARY KEY,
    event_id     uuid        NOT NULL,
    consumer     text        NOT NULL,
    event_type   text        NOT NULL,
    payload      jsonb       NOT NULL,
    error        text        NOT NULL,
    attempts     integer     NOT NULL,
    failed_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_event_dead_letter_failed_at ON event_dead_letter (failed_at DESC);

-- 4. Append-Only Financial Audit Log (populated by the audit-writer consumer; no PII or narration text)
CREATE TABLE audit_log (
    id             uuid PRIMARY KEY,
    event_id       uuid        NOT NULL UNIQUE,
    tenant_hash    varchar(64) NOT NULL,
    event_type     text        NOT NULL,
    aggregate_type text        NOT NULL,
    aggregate_id   text        NOT NULL,
    trace_id       text,
    occurred_at    timestamptz NOT NULL,
    recorded_at    timestamptz NOT NULL DEFAULT now(),
    summary_json   jsonb       NOT NULL
);

CREATE INDEX ix_audit_log_tenant_occurred ON audit_log (tenant_hash, occurred_at DESC);

-- 5. Support index for import_job_id lookups (deletion & reconciliation checks)
CREATE INDEX IF NOT EXISTS idx_transactions_import_job_id ON transactions (import_job_id) WHERE import_job_id IS NOT NULL;
