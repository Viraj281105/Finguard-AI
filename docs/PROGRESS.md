# Progress

**As of 2026-10-22.** Evidence-based: everything under "Verified" was observed by running the command shown.
Labels: see [README.md](README.md#status-vocabulary-used-in-every-document).

## 1. Snapshot

| | |
|---|---|
| Latest commit | `399fbbb` — merge of PR #16 (2026-03-17). No tags. |
| Working tree | **Stage 4 (Version 0.5.0) — AI Transaction Categorisation complete.** `POST /v1/classify` in the Python service backed by a deterministic, explainable **rule classifier (not an ML model)**; shared 19-id category taxonomy (`contracts/categories.json`) verified in Python, Java, SQL and TypeScript; backend persists category, confidence tier, calibration flag, rule id, version and review flag; **import persists first and classifies second**, and a user category is never overwritten (row-locked, tested); `PATCH /api/transactions/{id}/category` and `POST /api/transactions/reclassify`; Flyway **V5**; category UI (automatic vs confirmed, correction, review queue, graceful AI-down messages); synthetic-data evaluation harness and report. Working tree uncommitted. |
| Version | **0.5.0** aligned across backend, frontend, and AI service |
| Phase 1 — repository refactor | **Done** (70 backend, 49 frontend, 3 AI tests passed baseline) |
| Phase 2 — documentation & planning | **Done** (comprehensive `docs/` specifications) |
| Stage 0 — Foundation Close-Out | **Done** |
| Stage 1 — Version 0.2.0 | **Done** (83 backend tests, 52 frontend tests, 3 AI tests) |
| Stage 2 — Version 0.3.0 | **Done** (100 backend tests, 54 frontend tests, 3 AI tests) |
| Stage 3 — Version 0.4.0 | **Done** (114 backend tests, 55 frontend tests, 3 AI tests; 172 automated tests) |
| Stage 4 — Version 0.5.0 | **Done** (162 backend tests, 80 frontend tests, 121 AI tests; **363 automated tests**; builds, lint and type checks clean) |

## 2. Verified results

Baseline before any 0.5.0 change (2026-10-22): `.\scripts\test-all.ps1` → 114 backend + 55 frontend + 3 AI passed.

| Check | Command | Result (2026-10-22) |
|---|---|---|
| All suites | `.\scripts\test-all.ps1` | "All checks passed", exit 0 — backend, frontend typecheck/lint/tests/**production build**, AI `ruff`/pytest/**evaluation freshness** |
| Backend tests | `cd backend; .\mvnw -B test` | **162 passed, 0 failed, 0 skipped** — embedded PostgreSQL 15.19; includes the live Java ↔ Python tests (real FastAPI process) |
| Frontend type check | `npm run typecheck` | clean |
| Frontend lint | `npm run lint` | clean |
| Frontend tests & coverage | `npm run test:coverage` | **80 passed** (12 files); line coverage 90.9 % (Vitest v8 report) |
| Frontend build | `npm run build` | succeeds (≈ 340 kB JS, 107 kB gzip) |
| AI tests / lint | `.\.venv\Scripts\python -m pytest -q`, `ruff check .` | **121 passed**, clean (Python 3.14.7) |
| Classifier evaluation | `.\.venv\Scripts\python -m evaluation.evaluate` | **Synthetic data only.** First look at the 133-row hold-out file: 92.5 % accuracy, 99.1 % precision when asserted, 9 declined + 1 wrong. After the rule fixes made from those errors the file is no longer blind (100 %, meaningless). Details and caveats: `ai/evaluation/report.md` |
| Classification latency | same command | ≈ 25 ms in-process for 200 items on the development machine (excludes HTTP/JSON) |
| Live Java ↔ Python | `LiveAiServiceContractTest` (4), `LiveAiImportTest` (1) | pass against `uvicorn` started from `ai/.venv`; shared-secret refusal, 422 handling, recorded contract and full import → category → database verified |
| Compose syntax | `docker compose config` | valid |
| V5 migration with data | `LegacyDatabaseAdoptionTest` | pass — legacy free-text categories normalised without loss, constraints enforced |

**Not verified (never run):** `docker compose up` (PostgreSQL + AI containers), the AI Docker image build, `start.ps1`,
the backend dev profile against the Compose database, **the frontend in a real browser (including the new category UI)**,
the new CI steps on a CI runner, any proprietary bank export (KI-019), the classifier on **real** statements (KI-030).
See [KNOWN_ISSUES.md](KNOWN_ISSUES.md) §B.

## 3. Capability status

| Capability | Status | Evidence / limitation |
|---|---|---|
| Register / login / refresh | Implemented & tested | `AuthApiTest`, `JwtServiceTest`; 15m access token, 7d refresh token, rotation, reuse detection, HttpOnly SameSite=Strict cookie |
| Per-user isolation of transactions, investments, loans, upload jobs, categories | Implemented & tested | `OwnershipApiTest`, `ImportApiTest`, `CategoryApiTest` (PATCH on another user's transaction is 404; reclassify only sends the caller's rows) |
| CRUD for transactions, investments, loans | Implemented & tested | `LedgerApiTest`; pagination with headers |
| Statement import (5 banks) | Implemented & tested on synthetic data | KI-019: real exports unverified |
| De-duplication, transaction date | Implemented & tested | `TransactionFingerprint`, `ImportWorker`; a re-upload never re-classifies or alters a corrected category |
| **AI categorisation of imported transactions** | **Implemented & tested — rule baseline, not ML** | `POST /v1/classify`; `ImportClassificationTest`, `LiveAiImportTest`; AI down → import still `COMPLETED`, rows uncategorised, `classificationStatus` says so |
| **Category provenance** (`NONE`/`RULE`/`MODEL`/`USER`, rule id, version, review flag) | Implemented & tested | V5, `CategoryApiTest`; `MODEL` is never produced today |
| **User category correction / confirmation; user choice never overwritten** | Implemented & tested | `PATCH /api/transactions/{id}/category`, row-locked automatic apply, race test |
| **Re-classify uncategorised, "needs a category" / "needs review" filters** | Implemented & tested | `POST /api/transactions/reclassify`, `GET /api/transactions?category=UNCATEGORIZED|needsReview=true` |
| **Taxonomy consistency across Python / Java / SQL / TypeScript** | Implemented & tested | `contracts/categories.json` + one test per layer |
| **Calibrated confidence** | **Not available** | The score is a rule-strength tier; the API says `categoryConfidenceCalibrated: false`; the UI shows no percentage (KI-031) |
| **Trained ML model / measured real-world accuracy** | **Not implemented / unknown** | KI-030; no labelled real data (D-06) |
| Loan interest rate precision | Implemented & tested | `numeric(10,4)` |
| Migrations / profiles / environment configuration | Implemented & tested | V1–V5, `ProfilesTest`, `LegacyDatabaseAdoptionTest` |
| Architecture boundaries | Implemented & tested | `ArchitectureTest` (9 rules, includes `categorization`) |
| Frontend (auth, lists, add forms, import widget, dashboard, **category UI**) | Implemented & tested at unit/component level | Not browser-tested; layout and keyboard behaviour of the inline category picker are unverified |
| Analytics, risk score, forecast, scenarios, recommendations | **Not implemented** | KI-011 → v0.6.0–v0.10.0 |
| Transfers / refunds as *relationships* (linking, netting) | **Not implemented** | They are labelled, and excluded from client-side totals; KI-009 → v0.6.0 |
| Docker stack, `start.ps1` | Implemented, unverified | KI-018 |
| CI | Implemented; **0.5.0 changes not yet run on a runner** | `.github/workflows/ci.yml` now also starts the AI service for the backend job and checks evaluation metrics |
| Deployment | Not implemented | v1.0.0 |

## 4. Completed work

### Phase 1 — refactor (summary; details in [CHANGELOG.md](CHANGELOG.md))
- Backend reorganised into `common`, `identity`, `ledger`, `ingestion`, `ai` with enforced dependency rules.
- Flyway baseline, `validate` mode, dev/prod profiles, environment-based configuration.
- Fixed: cross-user data access, broken registration, non-async import, uncontrolled error responses.
- Statement import split into request-side service and background worker; parsers split per bank; atomic save.
- Frontend reorganised by feature with a shared layer; duplication removed; type/lint errors fixed; test suite added.
- Python service skeleton with tests; Docker Compose, `.env.example`, scripts.

### Phase 2 — documentation
Created under `docs/`: `README`, `ROADMAP`, `RELEASE_PLAN`, `PROGRESS`, `KNOWN_ISSUES`, `DECISIONS`, `ARCHITECTURE`, `API`, `DATABASE`,
`FINANCIAL_RULES`, `DEVELOPMENT`, `TESTING`, `VERSIONING`, `CHANGELOG`. Rewrote root `README.md`; replaced the template
`frontend/README.md`; added `ai/README.md`; marked `Week1.md` and `README.html` as historical.

### Stage 1 (v0.2.0) — Session Security & Delivery Hygiene
- Session security & rotation (15m access, 7d refresh, token rotation, reuse detection revoking family, server-side revocation table, logout endpoint, HttpOnly SameSite=Strict cookie).
- Frontend silent refresh with in-flight coalescing; brute-force protection rate limiter (429 + `Retry-After`); email normalization & Flyway V2 migration with `uq_users_lower_email`; HTTP error contracts; security headers; prod secret guard; CI workflow; JaCoCo & Vitest coverage tooling. Resolved KI-012, KI-013, KI-014, KI-015, KI-017, KI-023, KI-024, KI-027, KI-028, KI-029.

### Stage 2 (v0.3.0) — Dated, Idempotent Financial Ledger
- **Dedicated transaction date**: Added `LocalDate date` (`txn_date date NOT NULL`), separate from `createdAt`. Populated from statement dates across all bank parsers, accepted/returned in DTOs, defaulted to today for manual creation, and displayed in frontend (resolving KI-001).
- **Safe Flyway V3 migration**: Backfilled existing transaction dates from `Imported from ... | YYYY-MM-DD` notes or `created_at::date`; widened `loans.interest_rate` to `numeric(10,4)` (resolving KI-026); created composite index `idx_transactions_user_date` on `(user_id, txn_date DESC, created_at DESC)`, category index `idx_transactions_user_category`, and foreign key indexes on `upload_jobs`, `investments`, and `loans` (resolving KI-016).
- **Deterministic fingerprinting & deduplication**: User-scoped SHA-256 fingerprint generated from `userId|date|type|amount|normalizedTitle|occurrenceIndex`. Second and subsequent statement uploads skip duplicates; `duplicateRows` and `rowsProcessed` tracked in `UploadJob` and response; partial unique index `uq_transactions_user_fingerprint` ensures concurrent idempotency; foreign key `import_job_id` provides statement provenance (resolving KI-002).
- **Backward-compatible pagination & query filtering**: `GET /api/transactions` supports `page`, `size` (1-100), `from`, `to`, `type`, and `category` parameters with deterministic sorting (`date DESC, createdAt DESC`). Pagination metadata returned via standard HTTP headers (`X-Total-Count`, `X-Page`, `X-Page-Size`, `X-Total-Pages`) preserving top-level JSON array compatibility (resolving KI-016).
- **Frontend enhancements**: `TransactionsPage` renders formatted dates (`formatDate(tx.date)`), pagination controls ("Previous" / "Next", page status), and "Import" badges; `AddTransactionModal` includes date picker; `UploadProgress` reports duplicate entries skipped; `formatDate` parses local time to avoid timezone day shifts.
- **Verification**: 100 backend tests (+17), 54 frontend tests (+2), 3 AI tests (157 total automated tests, all passing).

### Stage 3 (v0.4.0) — Reliable Statement Ingestion
- **Header-driven dynamic parsing**: Replaced positional index parsing across all 5 bank parsers (HDFC, ICICI, SBI, Axis, Kotak) with `HeaderDrivenStatementParser`. Column roles (`DATE`, `DESCRIPTION`, `DEBIT`, `CREDIT`, `AMOUNT`, `CR_DR`, `BALANCE`) are dynamically resolved against normalized header alias sets, tolerating reordered columns, extra columns, and missing optional columns (resolving KI-003).
- **ICICI dual layout correction**: Corrected layout detection and parsing so both single-amount `Cr/Dr` and dual `Withdrawal/Deposit` layouts are recognized and cleanly parsed (resolving KI-005).
- **Robust amount & date parsing**: `CsvSupport.parseAmount` supports negative values (`-1,234.50`), parenthesized values (`(1,234.50)`), trailing `Dr`/`Cr` suffixes, currency symbols (`₹`, `$`, `€`, `£`, `Rs.`), and comma-separated thousands (resolving KI-006). Multi-pattern date parser handles ISO-8601 (`yyyy-MM-dd`), `dd/MM/yyyy`, `dd-MM-yyyy`, `dd-MMM-yyyy`, and `dd/MMM/yyyy`.
- **Reconciliation metrics**: `UploadJob` tracks `totalRows`, `rowsProcessed`, `duplicateRows`, `rejectedRows`, and `skippedRows` satisfying $\text{totalRows} = \text{rowsProcessed} + \text{duplicateRows} + \text{rejectedRows} + \text{skippedRows}$ (resolving KI-004). Captures `statement_from`, `statement_to`, and `closing_balance`.
- **Actionable row error reporting**: Up to 50 row errors recorded in `upload_jobs.row_errors_json` with row number, failure reason, and PII-sanitized snippet (card/account digits masked with `****`, truncated to max 100 characters).
- **Startup recovery**: `ImportRecoveryListener` triggers on `ApplicationReadyEvent`, transitions orphaned `PENDING`/`PROCESSING` jobs to `FAILED` with safe re-upload instructions (resolving KI-007).
- **Security & limits**: 5 MB file size limit and 10,000 row cap enforced; null-byte binary files rejected with 400 Bad Request; UTF-8 BOM, standard UTF-8, and Windows-1252 (CP1252) fallback decoding supported; executor saturation 503 handled with retry guidance (resolving KI-029).
- **Import history API**: Added `GET /api/upload/jobs` returning user-isolated upload job history.
- **Frontend enhancements**: `UploadProgress.tsx` displays detailed breakdown counts (imported, duplicates skipped, summary/zero skipped, rejected) and an expandable error inspector detailing row numbers and sanitized failure snippets.
- **Verification**: 114 backend tests (+14), 55 frontend tests (+1), 3 AI tests (172 total automated tests, all passing).

### Stage 4 (v0.5.0) — AI Transaction Categorisation (this change)
- **Python service:** `POST /v1/classify` with validated schemas (batch ≤ 500; id, description ≤ 1000 chars, type, amount) and an honest response
  (`category`, `confidence` tier, `confidence_calibrated`, `method`, `model_version`, `rule_id`, `needs_review`, `reason`, `alternatives`);
  `GET /health` reports `capabilities: ["classify"]` plus `ml_model: false`; optional `X-Internal-Token` shared secret; descriptions never logged.
- **Classifier:** normaliser + 582 explainable token-boundary rules (`rules-1.0.0`) with strength and priority, direction awareness from the
  taxonomy, business rules for card payments / refunds / own-account transfers, specific-over-generic resolution, and **explicit abstention**
  (unknown merchants, UPI to people, ATM cash, gateways, ties, credit-from-merchant). `Classifier` protocol + `ClassifierChain` so a trained model can
  complement it later; a test proves the seam.
- **Taxonomy:** 19 ids (the requested list plus `INSURANCE`, `CARD_PAYMENTS`, `REFUNDS`), each with a flow (`INCOME`/`EXPENSE`/`NON_SPENDING`/`REFUND`/`UNKNOWN`) and allowed directions;
  one shared file, verified in all four layers; transfers, card payments, investments and refunds are not ordinary expenses (FINANCIAL_RULES §5).
- **Backend:** new `categorization` module; `AiClient` validates every prediction, fails fast (2 failed batches / 90 s), logs failure kinds only; import
  **persists first, then classifies**; automatic categories are applied by `TransactionService.applyAutomaticCategories` under a row lock to rows still `NONE`;
  provenance persisted; import status gains `classificationStatus`/`classifiedRows`; `PATCH …/category`, `POST …/reclassify`, filters; `PUT` keeps an absent category.
- **Database:** **Flyway V5** — provenance columns, lossless normalisation of legacy categories, existing categories marked `USER`, three `CHECK` constraints, import-job columns.
- **Frontend:** category cell (Auto / Review / ✓ confirmed / needs a category), one-click confirm, inline correction limited to suitable categories,
  filters, "Categorize automatically" with success / partial / unavailable messages, import-summary notice, no percentage unless calibrated; types now match the API
  (`IMPORT`, `NONE|RULE|MODEL|USER`); totals skip non-spending categories.
- **Evaluation:** synthetic labelled data (59 dev + 133 hold-out rows), `python -m evaluation.evaluate`, `metrics.json` guarded by a test and CI, report with first-look result, post-hoc changes and limits.
- **Verification:** 162 backend (+48), 80 frontend (+25), 121 AI (+118) = 363 automated tests (+191), all passing; production build, ruff, type check and lint clean.
- **Deliberately not done:** no ML model (no labelled real data — decision A-13), no calibration, no OpenAPI export (a recorded contract and the taxonomy contract replace it), no analytics use of the flows (v0.6.0).

## 5. Outstanding issues (highest priority first)

Full list with evidence and target versions: [KNOWN_ISSUES.md](KNOWN_ISSUES.md).

1. **KI-030** — the classifier is rules only; real-world quality is unknown (synthetic evaluation, same author as the rules); no ML model.
2. **KI-009/010** — transfers/refunds are labelled but not linked or netted; dashboard arithmetic is still client-side (v0.6.0).
3. **KI-031/032** — confidence is a rule tier, not a probability; known category gaps (cash/ATM, payments to people, finite brand list).
4. **KI-018** — Docker stack unverified (gate for 0.1.0). **KI-019** — proprietary bank exports unverified.
5. **KI-033/034/035/036** — optional shared secret, synchronous classification, live contract tests need Python locally (and the CI change is unrun), `PUT` category semantics changed.

## 6. Blockers and open questions

| Item | Needed from | Blocks |
|---|---|---|
| Authorisation to **commit** and tag v0.4.0 and v0.5.0 (nothing has been committed or pushed) | Owner | everything downstream |
| Explicit approval to begin Stage 5 (v0.6.0: Analytics) | Owner | Stage 5 start |
| Anonymised real bank statements (all five banks) and a labelled sample (D-06) | Owner | any statement about real accuracy, a trained model, calibrated confidence |
| Docker Desktop running | Developer machine | KI-018, full container verification |
| Decision on `Food` → `DINING` for legacy categories (could have been groceries) | Owner | none (users can correct) |

## 7. Next steps

**Stage 4 (v0.5.0) is complete.** In accordance with execution rules:
- **STOP and wait for the owner's explicit approval before starting Stage 5 (v0.6.0: Analytics).**
- Do NOT commit, push, reset, or discard changes.

## 8. Update log

| Date | Change |
|---|---|
| 2026-10-22 | Phase 1 refactor completed and verified locally. |
| 2026-10-22 | Phase 2 documentation completed; this file created. |
| 2026-10-22 | Stage 0 Foundation Verification and Release Close-Out completed. All 70 backend, 49 frontend, and 3 AI tests verified green. Component versions aligned to 0.1.0. |
| 2026-10-22 | Repository-Wide Structural Cleanup and Modularization completed. Modularized backend `ledger` and `ingestion`; organized frontend into `app/router`, `features/`, and `shared/`; purged placeholder directories and all 28 tracked `.gitkeep` files (resolving KI-021). |
| 2026-10-22 | Stage 1 (Version 0.2.0) Session Security & Delivery Hygiene completed. Refresh-token rotation, server-side revocation table (`refresh_tokens`), reuse detection, rate limiting (429 + `Retry-After`), email normalization & case-insensitive uniqueness index with Flyway V2 migration, HTTP error contracts (404, 405, 406, 413, 415), security headers, prod secret guard against non-local DBs, CI workflow, JaCoCo and Vitest coverage tooling. Resolved KI-012, KI-013, KI-014, KI-015, KI-017, KI-023, KI-024, KI-027, KI-028, KI-029. 83 backend tests, 52 frontend tests, 3 AI tests all pass cleanly. |
| 2026-10-22 | Stage 2 (Version 0.3.0) Dated, Idempotent Financial Ledger completed. Real transaction dates (`txn_date date NOT NULL`), statement transaction date persistence, safe Flyway V3 backfill, deterministic SHA-256 fingerprinting and repeat-upload deduplication, concurrency-safe partial index, statement provenance (`import_job_id` FK), backward-compatible pagination and filtering on `GET /api/transactions` with standard headers (`X-Total-Count`, `X-Page`, `X-Page-Size`, `X-Total-Pages`), wider loan interest rate (`numeric(10,4)`), composite and FK indexes, frontend date display and pagination controls with boundary handling, duplicate count display in import progress. Resolved KI-001, KI-002, KI-016, KI-026. 100 backend tests (+17), 54 frontend tests (+2), 3 AI tests (157 total automated tests, all passing). |
| 2026-10-22 | Stage 3 (Version 0.4.0) Reliable Statement Ingestion completed. Dynamic header-based column mapping across all 5 bank parsers (HDFC, ICICI, SBI, Axis, Kotak) resolving KI-003; ICICI withdrawal/deposit layout bug resolved (KI-005); comprehensive amount (negatives, parentheses, Dr/Cr suffixes, currency symbols) and multi-pattern date parsing resolving KI-006; UTF-8 BOM, standard UTF-8, and Windows-1252 fallback decoding; binary null-byte rejection and 5MB / 10,000 row limits; transparent reconciliation metrics (`totalRows = rowsProcessed + duplicateRows + rejectedRows + skippedRows`) resolving KI-004; actionable row-level error reporting with PII snippet sanitization; startup recovery of interrupted jobs resolving KI-007; HTTP 503 executor queue saturation and 413 oversize error handling resolving KI-029; `GET /api/upload/jobs` user-isolated import history; frontend reconciliation breakdown and expandable row error inspector; Flyway V4 migration (`V4__resilient_ingestion_reports.sql`). 114 backend tests (+14), 55 frontend tests (+1), 3 AI tests (172 total automated tests, all passing). |
| 2026-10-22 | Stage 4 (Version 0.5.0) AI Transaction Categorisation completed. `POST /v1/classify` with a deterministic rule classifier (`rules-1.0.0`, 582 rules, explicit abstention; **not an ML model**), shared 19-id taxonomy verified in Python/Java/SQL/TypeScript, category provenance persisted, import persists-then-classifies, user categories never overwritten (row lock + race test), `PATCH /api/transactions/{id}/category`, `POST /api/transactions/reclassify`, Flyway V5 (lossless category normalisation, constraints), optional `X-Internal-Token`, category UI with automatic-vs-confirmed states and graceful AI-down handling, synthetic-data evaluation (first-look hold-out 92.5 %, optimistic, see report). Resolved KI-008, KI-025; opened KI-030 – KI-036. 162 backend (+48), 80 frontend (+25), 121 AI (+118) = 363 automated tests (+191), all passing; build, lint, type check clean. Not committed. |
