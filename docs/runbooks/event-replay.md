# Event Outbox, Consumer Inbox & Dead-Letter Replay Runbook (S1-02)

## 1. Overview

FinGuard-AI uses a **PostgreSQL Transactional Outbox + Consumer Inbox** pattern (`event_outbox`, `processed_event`, `event_dead_letter`) to decouple derived projections and audit records (`audit_log`) from authoritative ledger transactions without introducing an external message broker (ADR-004).

- **Producer Atomicity:** Domain events (`com.finguard.import.completed`, `com.finguard.ledger.period_changed`, `com.finguard.transaction.category_reviewed`, `com.finguard.account.deletion_requested`) are inserted into `event_outbox` inside the same database transaction as the ledger write.
- **At-Least-Once Delivery / Effectively-Once Effect:** Each `DomainEventConsumer` executes inside a transaction guarded by `INSERT INTO processed_event (consumer, event_id) VALUES (?, ?) ON CONFLICT DO NOTHING`.
- **SLO-5 Lag Monitoring:** `finguard_outbox_oldest_pending_seconds` measures the age of the oldest unpublished row in `event_outbox` (`WHERE published_at IS NULL`). Alert `FinGuardOutboxProjectionLagBreach` fires if lag exceeds `60s` for `2m`.

---

## 2. Inspecting Pending & Dead-Lettered Events

### Check Unpublished Outbox Queue
```sql
SELECT id, event_type, tenant, occurred_at, attempts, last_error
FROM event_outbox
WHERE published_at IS NULL
ORDER BY occurred_at ASC;
```

### Check Dead-Letter Queue (`event_dead_letter`)
```sql
SELECT id, event_id, consumer, event_type, error, attempts, failed_at
FROM event_dead_letter
ORDER BY failed_at DESC
LIMIT 50;
```

---

## 3. Replaying Failed or Historical Events

Because every consumer is idempotent and recomputes state rather than applying blind deltas, events can be safely replayed by clearing the `processed_event` receipt for the target consumer and resetting `published_at` on `event_outbox`:

```sql
BEGIN;

-- 1. Remove the inbox deduplication receipt for the specific consumer and event(s)
DELETE FROM processed_event
WHERE consumer = 'audit-writer'
  AND event_id = '<event-uuid>';

-- 2. Re-open the outbox row so the scheduled relay (every 5s) re-delivers it
UPDATE event_outbox
SET published_at = NULL,
    attempts = 0,
    last_error = NULL
WHERE id = '<event-uuid>';

-- 3. Optional: clean up resolved dead-letter entry
DELETE FROM event_dead_letter
WHERE event_id = '<event-uuid>'
  AND consumer = 'audit-writer';

COMMIT;
```

---

## 4. Emergency Kill-Switch (`events.enabled=false`)

If a consumer defect causes unexpected load, set `EVENTS_ENABLED=false` (or `events.enabled=false`) to make `OutboxEventService` a no-op without rolling back the V8 schema migration.
