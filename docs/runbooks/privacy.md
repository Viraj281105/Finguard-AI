# Privacy Lifecycle, Account Export, Ordered Deletion & Data Retention Runbook (`S1-05`)

## 1. Overview & User-Facing Privacy Summary

FinGuard-AI provides self-service data portability, right-to-erasure with a 30-day grace window, and an append-only, narration-free audit trail under `/api/privacy`:

* **Right to Access & Data Portability (`POST /api/privacy/export`, `POST /api/privacy/export/csv`):**
  Users can export 100% of their personal and financial records (`profile`, `transactions`, `investments`, `loans`, `uploadJobs`, `budgets`, `goals`, `goalContributions`, `savedScenarios`) in structured JSON or RFC 4180 CSV format. Every CSV field beginning with `=`, `+`, `-`, `@`, `\t`, or `\r` (including after leading whitespace) is neutralised with a leading `'` to prevent spreadsheet formula injection.
* **Right to Erasure (`POST /api/privacy/delete-request`, `POST /api/privacy/delete-cancel`):**
  Requesting account deletion requires current-password re-authentication, immediately revokes all active refresh tokens, and schedules hard erasure after a **30-day grace period** (`app.privacy.deletion-grace-days=30`). Users may cancel a pending deletion during the grace period via `POST /api/privacy/delete-cancel` or request immediate erasure (`"immediate": true`).
* **Append-Only Audit Trail (`GET /api/privacy/audit-log`):**
  Every security- and privacy-relevant event (`com.finguard.auth.logged_in`, `com.finguard.account.exported`, `com.finguard.account.deletion_requested`, `com.finguard.account.deletion_cancelled`, `com.finguard.account.deleted`, `com.finguard.import.completed`, `com.finguard.ledger.period_changed`, `com.finguard.transaction.category_reviewed`) is recorded in `audit_log` keyed by a pseudonymous HMAC-SHA256 tenant hash (`tenant_hash`). `audit_log` never stores raw emails, user names, or transaction narrations, and is protected by the PostgreSQL trigger `trg_audit_log_append_only` against `UPDATE` and `DELETE`.

---

## 2. Deterministic FK-Safe Hard Deletion Order

Because `V1__baseline.sql` and `V3__dated_idempotent_ledger.sql` define foreign keys from `transactions`, `upload_jobs`, `loans`, and `investments` to `users` (and `transactions.import_job_id` to `upload_jobs`) without `ON DELETE CASCADE`, `PrivacyService.executeHardDelete` deletes user data in a single transaction in the following strict topological order:

1. `DELETE FROM goal_contributions WHERE user_id = ?`
2. `DELETE FROM financial_goals WHERE user_id = ?`
3. `DELETE FROM budgets WHERE user_id = ?`
4. `DELETE FROM saved_scenarios WHERE user_id = ?`
5. `DELETE FROM transactions WHERE user_id = ?` *(must precede `upload_jobs`)*
6. `DELETE FROM upload_jobs WHERE user_id = ?`
7. `DELETE FROM loans WHERE user_id = ?`
8. `DELETE FROM investments WHERE user_id = ?`
9. `DELETE FROM refresh_tokens WHERE user_id = ?`
10. `DELETE FROM users WHERE id = ?`

After the 10 deletes complete, `com.finguard.account.deleted` is written to `event_outbox` and delivered to `audit_log` under `tenant_hash`.

---

## 3. Data Retention Schedule (`DataRetentionService`)

`DataRetentionService` runs nightly at `03:30` (`app.privacy.retention-cron=0 30 3 * * ?`) and enforces:

| Target | Retention Window | Property | Action |
|---|---|---|---|
| Expired deletion grace periods (`users.deletion_scheduled_at <= now()`) | 30 days after request | `app.privacy.deletion-grace-days` | Calls `PrivacyService.executeHardDelete(userId, "GRACE_EXPIRED")` |
| `upload_jobs.row_errors_json` | 90 days | `app.privacy.row-errors-retention-days` | Sets `row_errors_json = NULL` for jobs older than 90 days |
| Published `event_outbox` rows (`published_at IS NOT NULL`) | 14 days | `app.privacy.outbox-retention-days` | `DELETE FROM event_outbox WHERE published_at < cutoff` |
| `processed_event` inbox deduplication rows | 30 days | `app.privacy.inbox-retention-days` | `DELETE FROM processed_event WHERE processed_at < cutoff` |
| `audit_log` (hashed tenant, no PII) | 365 days | `app.privacy.audit-log-retention-days` | Retained append-only; encrypted backups expire on a 35-day lifecycle window |

---

## 4. Operational Verification Commands

```powershell
# Run the full privacy lifecycle, cross-tenant export, formula-injection, and retention suite:
cd backend
.\mvnw.cmd test -Dtest=PrivacyLifecycleTest

# Regenerate and verify OpenAPI 3.1 contract + TypeScript client:
.\mvnw.cmd test -Dtest=OpenApiContractTest -Dopenapi.update=true
```

