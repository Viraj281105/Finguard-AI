# PostgreSQL 18 Backup & Restore Runbook (S0-09)

## 1. Purpose & Scope

This runbook defines the standard operating procedure for creating logical backups of the authoritative FinGuard-AI PostgreSQL 18 database, restoring a backup into a clean target instance, and verifying post-restore data integrity and financial ledger invariants.

| Metric | Target | Rehearsed Baseline (PostgreSQL 18.6, S0-09) |
|---|---|---|
| **Backup Format** | PostgreSQL Custom Archive (`pg_dump -Fc --no-owner --no-privileges`) + SHA-256 manifest | Verified (`BackupRestoreDrillTest`) |
| **Restore RTO (Logical Dump)** | $\le 15\text{ minutes}$ (staging/single-node), $\le 2\text{ hours}$ (production DR) | $< 1\text{ second}$ (synthetic seed dataset across all 11 tables) |
| **Data Fidelity Gate** | $100\%$ table row-count match + deterministic per-table `md5` checksum match + exact `BigDecimal` financial sum invariants | $11/11$ tables matched (`0` delta) |

---

## 2. Creating a Backup

### 2.1 Automated Script (Linux / macOS / CI)

```bash
chmod +x scripts/backup-db.sh
./scripts/backup-db.sh
```

### 2.2 Automated Script (Windows PowerShell)

```powershell
.\scripts\backup-db.ps1
```

Both scripts produce:
- `.backups/finguard-<UTC-timestamp>.dump` (PostgreSQL 18 custom compressed archive)
- `.backups/finguard-<UTC-timestamp>.dump.sha256` (SHA-256 integrity digest)

### 2.3 Direct CLI Command (Standalone PostgreSQL 18)

```bash
pg_dump -Fc --no-owner --no-privileges \
  -h "${PGHOST}" -p "${PGPORT:-5432}" -U "${PGUSER}" -d "${PGDATABASE}" \
  -f "finguard-$(date -u +%Y%m%dT%H%M%SZ).dump"
```

---

## 3. Restoring from a Backup

### 3.1 Pre-Restore Checklist

1. **Verify the SHA-256 digest** of the `.dump` archive before running `pg_restore`:
   ```bash
   sha256sum -c finguard-<timestamp>.dump.sha256
   ```
2. **Stop or drain application writes** (`backend` replicas scaled to `0` or maintenance mode enabled) so no concurrent transactions write to the old database during cutover.
3. **Provision or recreate a clean PostgreSQL 18 target database** with UTF-8 encoding.

### 3.2 Restore Execution

```bash
pg_restore --no-owner --no-privileges --exit-on-error \
  -h "${TARGET_PGHOST}" -p "${TARGET_PGPORT:-5432}" \
  -U "${TARGET_PGUSER}" -d "${TARGET_PGDATABASE}" \
  finguard-<timestamp>.dump
```

---

## 4. Post-Restore Verification Gate

Before re-enabling traffic on `backend`, execute the following verification checks against the restored database:

### 4.1 Flyway Schema History Integrity

```sql
SELECT installed_rank, version, description, type, success
FROM flyway_schema_history
ORDER BY installed_rank;
```
Confirm every migration (`V1` through `V7`) has `success = true`.

### 4.2 Per-Table Row Counts & Deterministic MD5 Checksums

For every table in `public` (`flyway_schema_history`, `users`, `refresh_tokens`, `transactions`, `loans`, `investments`, `upload_jobs`, `budgets`, `financial_goals`, `goal_contributions`, `saved_scenarios`):

```sql
SELECT count(*) AS row_count,
       coalesce(md5(string_agg(t::text, '|' ORDER BY 1)), 'EMPTY') AS table_md5
FROM (SELECT * FROM transactions ORDER BY 1) t;
```

### 4.3 Authoritative Financial Ledger Invariants

Verify exact `DECIMAL` totals before opening traffic:

```sql
SELECT
  (SELECT coalesce(sum(amount), 0) FROM transactions WHERE type = 'INCOME')        AS total_income,
  (SELECT coalesce(sum(amount), 0) FROM transactions WHERE type = 'EXPENSE')       AS total_expense,
  (SELECT coalesce(sum(principal), 0) FROM loans WHERE active = true)              AS active_loan_principal,
  (SELECT coalesce(sum(current_value), 0) FROM investments)                        AS total_investments,
  (SELECT coalesce(sum(amount), 0) FROM budgets)                                   AS total_budgets,
  (SELECT coalesce(sum(current_amount), 0) FROM financial_goals)                   AS total_goal_progress,
  (SELECT coalesce(sum(amount), 0) FROM goal_contributions)                        AS total_goal_contributions;
```

---

## 5. Automated Rehearsal

- **In-Process JUnit Drill (runs on every `mvnw test`):** [`BackupRestoreDrillTest`](../../backend/src/test/java/com/finguard/backend/migration/BackupRestoreDrillTest.java) seeds all 11 tables on PostgreSQL 18.6, runs `pg_dump -Fc` and `pg_restore --exit-on-error`, and asserts exact row counts, per-table MD5 checksums, and `BigDecimal` financial sums.
- **Containerised Drill Script:** `./scripts/restore-drill.sh` (Linux/CI) and `.\scripts\restore-drill.ps1` (Windows PowerShell).
