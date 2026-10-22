# Changelog

Format: [Keep a Changelog](https://keepachangelog.com); versioning: [VERSIONING.md](VERSIONING.md).
Only work that **exists in the repository** is listed. Planned releases are in [RELEASE_PLAN.md](RELEASE_PLAN.md).
No version has been tagged yet; v0.1.0 is pending (see [PROGRESS.md](PROGRESS.md)).

## [0.5.0] — AI Transaction Categorisation

Working-tree changes for Stage 4. **Nothing here is committed.** The classifier is a deterministic **rule baseline,
not a machine-learning model**; see [FINANCIAL_RULES.md](FINANCIAL_RULES.md#5-transaction-categories) and
[`ai/evaluation/report.md`](../ai/evaluation/report.md).

### Added
- **AI service:** `POST /v1/classify` (validated schemas; batch ≤ 500; `id`, `description`, optional `type`/`amount`) returning
  category, `confidence` (rule-strength tier), `confidence_calibrated`, `method`, `model_version`, `rule_id`, `needs_review`,
  `reason`, `alternatives`. Deterministic rule classifier (`rules-1.0.0`) with text normalisation, direction-aware
  business rules (card payments, refunds, transfers), specific-over-generic priorities, and **explicit abstention** for
  unknown merchants, ambiguity, UPI payees, cash and gateways. `Classifier` protocol + `ClassifierChain` for a future model.
  Optional shared secret (`X-Internal-Token` / `AI_INTERNAL_TOKEN`). `/health` reports `capabilities: ["classify"]` and
  the classifier kind (`ml_model: false`).
- **Taxonomy:** 19 category ids shared via `contracts/categories.json` (Python, Java `TransactionCategory`, SQL `CHECK`, TypeScript), each verified by a test.
- **Backend:** new `categorization` module; `AiClient` returns validated predictions (`ClassifyOutcome`), fails fast
  after two failed batches or 90 s, logs failure kinds only; import now **persists first, then classifies**, and
  applies categories only to rows still uncategorised; provenance persisted (source, confidence, calibration flag, rule,
  version, review flag, timestamp); `PATCH /api/transactions/{id}/category`; `POST /api/transactions/reclassify`;
  list filters `category=UNCATEGORIZED` and `needsReview=true`; import status gains `classificationStatus` and `classifiedRows`.
- **Migration V5** (`V5__transaction_categorisation.sql`): provenance columns, normalisation of legacy free-text
  categories (lossless), pre-existing categories marked `USER`, `CHECK` constraints for taxonomy, provenance consistency and
  confidence range, `upload_jobs` classification columns. Verified with data by `LegacyDatabaseAdoptionTest`.
- **Frontend:** category cell showing automatic (`Auto`, `Review`) versus user-confirmed (✓) categories, one-click confirm,
  inline correction limited to categories that suit the direction, "Needs a category" / "Needs review" filters,
  "Categorize automatically" with explicit success/partial/unavailable messages, import summary line for
  categorisation, confidence shown only when calibrated.
- **Evaluation:** labelled synthetic datasets, `python -m evaluation.evaluate` (accuracy, coverage, precision, macro-F1,
  per-class, confusion matrix, errors, latency) writing `metrics.json` and `report.md`; `--check` fails CI when stale.
- **Tests:** 191 new automated tests (see [TESTING.md](TESTING.md)), including the real Java ↔ Python process and a recorded
  contract shared by both sides. `scripts/test-all.ps1` now also runs the production build, `ruff` and the evaluation check.

### Changed
- `TransactionResponse` adds `categoryConfidenceCalibrated`, `categoryNeedsReview`, `categoryModelVersion`, `categoryRule`
  (additive). `category` values are now canonical ids (`DINING`, not `Food`); legacy labels are accepted on input.
- **`PUT /api/transactions/{id}`:** an absent `category` now keeps the current category (it used to clear it); `""` clears (KI-036).
- Frontend types now match the backend (`source: "MANUAL" | "IMPORT"`, `categorySource: "NONE" | "RULE" | "MODEL" | "USER"`);
  the old values never matched what the API returns, so the "Import" badge never showed.
- Transactions page and dashboard totals leave out transfers, card payments, investments and refunds.
- Versions aligned to 0.5.0 (backend, frontend, AI service).

### Fixed
- An update that cleared a category used to leave a stale `category_source`; V5 repairs existing rows and a `CHECK`
  constraint prevents it.
- Response bodies from the AI service (which can echo transaction text) are no longer written to the backend log.

### Resolved issues
KI-008, KI-025. New: KI-030 – KI-036 (see [KNOWN_ISSUES.md](KNOWN_ISSUES.md)).

## [0.4.0] — Reliable Statement Ingestion

Working-tree changes for Stage 3. **Nothing here is committed.**

### Added
- **Dynamic Header-Driven Column Mapping**: Replaced fragile index-based parsing across all 5 bank parsers (HDFC, ICICI, SBI, Axis, Kotak) with `HeaderDrivenStatementParser`. Column roles (`DATE`, `DESCRIPTION`, `DEBIT`, `CREDIT`, `AMOUNT`, `CR_DR`, `BALANCE`) are discovered dynamically by matching normalized header names against alias dictionaries, tolerating reordered columns, missing optional columns, and extra metadata (KI-003).
- **ICICI Dual Layout Support**: Corrected layout detection and parsing so both single-amount `Cr/Dr` and dual `Withdrawal/Deposit` layouts are recognized and parsed cleanly (KI-005).
- **Comprehensive Amount and Date Parsing**:
  - `CsvSupport.parseAmount` parses negative amounts (`-1,234.50`), parenthesized amounts (`(1,234.50)`), trailing `Dr`/`Cr` indicators, currency symbols (`₹`, `$`, `€`, `£`, `Rs.`), and comma-separated thousands (KI-006).
  - Multi-pattern date parser handles ISO-8601 (`yyyy-MM-dd`), `dd/MM/yyyy`, `dd-MM-yyyy`, `dd-MMM-yyyy`, and `dd/MMM/yyyy` (case-insensitive month names).
- **Transparent Import Reconciliation Metrics**:
  - `UploadJob` entity, database, and API response track `totalRows`, `rowsProcessed`, `duplicateRows`, `rejectedRows`, and `skippedRows` satisfying the invariant $\text{totalRows} = \text{rowsProcessed} + \text{duplicateRows} + \text{rejectedRows} + \text{skippedRows}$ (KI-004).
  - Statement period (`statement_from`, `statement_to`) and closing balance (`closing_balance`) recorded from parsed rows.
- **Actionable Row-Level Error Reporting with PII Sanitization**:
  - Records granular row validation errors (`rowNumber`, `reason`, `snippet`) for rejected malformed rows.
  - Sanitizes snippets with regex masking replacing account/card digits with `****` and truncating snippets to max 100 characters to prevent PII exposure in logs and API responses.
  - Bounded storage up to 50 row errors in `upload_jobs.row_errors_json`.
- **Import History API**: Added `GET /api/upload/jobs` returning a list of upload jobs scoped strictly to the authenticated user.
- **Interrupted Job Startup Recovery**: `ImportRecoveryListener` listening for `ApplicationReadyEvent` detects orphaned `PENDING` and `PROCESSING` upload jobs from an unexpected application restart and transitions them to `FAILED` with safe re-upload instructions (KI-007).
- **File Validation & Safety Limits**:
  - Enforced 5 MB upload size limit and 10,000 maximum row limit per statement.
  - Null-byte binary file detection rejecting non-text files with 400 Bad Request.
  - Character set detection supporting UTF-8, UTF-8 BOM, and Windows-1252 (CP1252) fallback decoding.
  - Handled executor saturation returning HTTP 503 `ApiError` with retry guidance (KI-029).
- **Frontend Statement Import Enhancements**:
  - `UploadProgress.tsx` displays full reconciliation breakdown: imported rows, duplicates skipped, intentional zero/summary rows skipped, and rejected rows.
  - Added expandable error inspector displaying row numbers, failure reasons, and sanitized snippets for all rejected rows.
- **Database Migration `V4__resilient_ingestion_reports.sql`**: Adds `rejected_rows`, `skipped_rows`, `row_errors_json`, `closing_balance`, `statement_from`, and `statement_to` to `upload_jobs`.

### Changed
- Aligned module versions to `0.4.0` in `backend/pom.xml`, `frontend/package.json`, `ai/pyproject.toml`, and `ai/app/__init__.py`.
- Expanded automated test suite: 114 backend tests (+14), 55 frontend tests (+1), 3 AI tests (172 total automated tests, all passing).

---

## [0.3.0] — Dated, Idempotent Financial Ledger

Working-tree changes for Stage 2. **Nothing here is committed.**

### Added
- **Dedicated Financial Transaction Dates**: Real calendar date (`LocalDate date`, DB column `txn_date date NOT NULL`) separate from `createdAt`. Populated from parsed statement dates across all bank parsers, exposed via API request/response DTOs, and displayed in the frontend (KI-001).
- **Reliable Duplicate Detection**: Deterministic SHA-256 fingerprint generated from `userId|date|type|amount|normalizedTitle|occurrenceIndex`. Re-uploading the same statement or overlapping statements skips duplicate transactions without failing the job (KI-002).
- **Concurrency-Safe Idempotency**: DB partial unique index `uq_transactions_user_fingerprint ON transactions (user_id, fingerprint) WHERE fingerprint IS NOT NULL` protects against race conditions during concurrent imports.
- **Import Provenance**: `transactions.import_job_id` foreign key links imported transactions directly to their originating `upload_jobs.id` (`ON DELETE SET NULL`), resolving KI-002.
- **Import Duplicate Reporting**: `UploadJob` entity and `UploadStatusResponse` track `duplicateRows` alongside `rowsProcessed`. Frontend upload progress highlights skipped duplicate counts.
- **Backward-Compatible Pagination & Query Filtering**: `GET /api/transactions` supports `page`, `size` (1-100), `from`, `to`, `type`, and `category` query parameters with deterministic sorting (`date DESC, createdAt DESC`). Pagination metadata is returned via HTTP headers (`X-Total-Count`, `X-Page`, `X-Page-Size`, `X-Total-Pages`), preserving top-level JSON array compatibility (KI-016).
- **Category Provenance Columns**: Added `category_source`, `category_confidence`, and `category_model_version` to `transactions` preparing for model classification.
- **Database Migration `V3__dated_idempotent_ledger.sql`**: Backfills transaction dates from note patterns (`YYYY-MM-DD`) or `created_at::date`; widens `loans.interest_rate` to `numeric(10,4)`; creates composite index `idx_transactions_user_date`, `uq_transactions_user_fingerprint`, `idx_transactions_user_category`, and foreign key indexes on `upload_jobs`, `investments`, and `loans`.
- **Loan Interest Rate Precision**: Widened `loans.interest_rate` to `numeric(10,4)` in database and entity, supporting rates up to 4 decimal places (e.g. `8.7550%`) (KI-026).
- **Frontend Enhancements**:
  - `TransactionsPage` displays formatted dates (`formatDate(tx.date)`), pagination controls (`Previous` / `Next`, page status), and "Import" badges for statement-imported transactions.
  - `AddTransactionModal` includes date picker field defaulting to today's date.
  - `formatDate` parses `YYYY-MM-DD` as local time, preventing negative UTC offset day-shift bugs.

### Changed
- Aligned module versions to `0.3.0` in `backend/pom.xml`, `frontend/package.json`, `ai/pyproject.toml`, and `ai/app/__init__.py`.
- Expanded automated test suite: 100 backend tests (+17), 54 frontend tests (+2), 3 AI tests (157 total automated tests, all passing).

---

## [0.2.0] — Session Security & Delivery Hygiene

Working-tree changes for Stage 1. **Nothing here is committed.**

### Added
- **Refresh Token Rotation & Revocation**: Single-use refresh tokens stored hashed with SHA-256 in new `refresh_tokens` table. Each refresh issues a new token pair and replaces the old token. Reusing an old/revoked token triggers reuse detection, revoking all active sessions for the compromised user account (D-03b).
- **Session Revocation & Logout**: `POST /api/auth/logout` endpoint invalidating active refresh tokens server-side.
- **Cookie-Based Refresh & CSRF Protection**: Refresh tokens are returned in an `HttpOnly`, `SameSite=Strict` cookie scoped to `/api/auth` with optional payload fallback.
- **Frontend Silent Refresh**: Axios response interceptor intercepts 401 responses on protected routes, coalesces concurrent 401 requests into a single in-flight refresh call, retries queued requests once, and clears session state on refresh failure.
- **Authentication Rate Limiting**: In-memory IP + email sliding rate limiter throttling after 5 failed authentication attempts with HTTP `429 Too Many Requests` and standard `Retry-After: 60` response header (D-04).
- **Database Migration `V2__auth_refresh_tokens_and_email_index.sql`**: Creates `refresh_tokens` table, normalizes existing emails to lower(trim(email)), asserts zero case-variant duplicate emails exist (failing fast if detected), and creates unique index `uq_users_lower_email ON users (lower(email))` (D-05).
- **Email Normalization & Password Rules**: Lowercase and trimmed email normalization enforced at domain entity lifecycle (`@PrePersist`, `@PreUpdate`) and DTO levels. Registration password minimum length raised to 10 characters (D-02).
- **HTTP Error Handling Contract**: Explicit handling for 404 (`NoResourceFoundException`), 405 (`HttpRequestMethodNotSupportedException`), 415 (`HttpMediaTypeNotSupportedException`), 406 (`HttpMediaTypeNotAcceptableException`), 413 (`MaxUploadSizeExceededException`), and 429 (`RateLimitExceededException`) returning standard `ApiError` responses (KI-014, KI-029).
- **Security Headers**: Configured `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: strict-origin-when-cross-origin`, and `Content-Security-Policy: default-src 'self'; frame-ancestors 'none';` across all responses (KI-013).
- **Production Secret Guard**: `SecurityEnvironmentGuard` `ApplicationContextInitializer` failing fast at startup if the default/development JWT secret is used against any non-local database host (KI-028).
- **Continuous Integration**: `.github/workflows/ci.yml` running backend Maven tests & JaCoCo coverage, frontend TypeScript check, ESLint, Vitest coverage, production build, npm audit, and AI service Ruff linter & Pytest (KI-017).
- **Coverage Tooling**: Configured `jacoco-maven-plugin:0.8.12` generating coverage reports in `backend/target/site/jacoco` and `@vitest/coverage-v8` in frontend with `npm run test:coverage` (KI-027).

### Changed
- **BREAKING (token lifetime)**: Access token lifetime reduced from 24 hours to **15 minutes** (900,000 ms); refresh token lifetime is **7 days** (604,800,000 ms).
- **BREAKING (passwords)**: Minimum password length raised from 6 to **10 characters**.
- Aligned module versions to `0.2.0` in `backend/pom.xml`, `frontend/package.json`, `ai/pyproject.toml`, and `ai/app/__init__.py`.
- Configured Maven Surefire `argLine` with `-XX:+EnableDynamicAgentLoading` to eliminate Mockito agent attachment warnings on modern JDKs (KI-024).
- Removed redundant `DaoAuthenticationProvider` bean definition to eliminate Spring Security duplicate provider warning (KI-024).
- Updated vulnerable frontend transitive dependencies via `npm audit fix`, resolving 17 vulnerabilities (KI-023).

---

## [0.1.0] — Foundation

Working-tree changes on top of commit `399fbbb` (merge of PR #16).

### Added
- **Database migrations**: Flyway with `V1__baseline.sql` reproducing the previous schema; databases created by the
  old `ddl-auto=update` setup are adopted as baseline without data loss (tested with rows).
- **Profiles and configuration**: `dev` (default) and `prod` profiles; configuration through environment variables;
  startup fails if `JWT_SECRET` is missing/short outside `dev`; root and frontend `.env.example`.
- **Auth**: token type claim (`typ`) so a refresh token cannot call the API; `POST /api/auth/refresh`.
- **Error contract**: uniform JSON error body with per-field validation errors; 401/403 produced by the security chain in the same format.
- **Import**: bounded background worker pool; `503` when the queue is full; 5 MB upload limit configured server-side; byte-order-mark tolerance.
- **AI client**: batching (200), configurable URL/timeouts, tolerant of malformed/duplicate/blank results.
- **Python service skeleton** (`ai/`): FastAPI app factory, `GET /health`, `pyproject.toml`, Dockerfile, tests.
- **Frontend**: Vitest + Testing Library test setup (49 tests); `typecheck` and `test` scripts; shared API client with 401 handling; token-expiry check in the route guard; `VITE_API_BASE_URL`.
- **Backend tests** (70): auth API, JWT, ownership isolation, ledger API, import API end to end on a real database, parsers, AI client, migrations, profiles, architecture rules. Tests run on embedded PostgreSQL 15 (no Docker needed).
- **Tooling**: Docker Compose PostgreSQL healthcheck and `ai` service; `scripts/test-all.ps1`; rewritten `start.ps1`.
- **Documentation**: the `docs/` set; rewritten `README.md`; status banners on `Week1.md` and `README.html`; `frontend/README.md` and `ai/README.md`.

### Changed
- **BREAKING (clients/sessions):** existing browser sessions are invalidated — tokens now carry a type claim and the development signing key changed. Users log in again.
- **BREAKING (local setup):** the Compose database is published on host port **5433** (was 5432); the dev-profile datasource default follows. Volume and credentials are unchanged.
- **BREAKING (responses):** `DELETE` of a missing or foreign record now returns `404` (by code reading it was `204` before; not run); bad credentials return `401`, duplicate registration `409`, invalid input `400` with `fieldErrors` (the old code had no error handling for these, so the old status codes were not observed).
- Another user's transactions, investments, loans and upload jobs are reported as `404` (see Security).
- Component versions aligned to `0.1.0` across `backend/pom.xml`, `frontend/package.json`, and `ai/pyproject.toml` (resolving KI-022).
- Backend reorganised from layers (`controller/service/model/…`) to domain modules `common`, `identity`, `ledger`, `ingestion`, `ai`; dependency rules enforced by ArchUnit. Package names changed; HTTP paths did not.
- Entities are no longer serialised directly; explicit request/response records keep the same JSON field names.
- Statement import: classification now runs **before** the single atomic save (no partial imports); parser errors are shown to the user, other failures get a generic message; the AI client path is `/v1/classify` (the uncommitted draft used `/classify`).
- Loan `interestRate` of `0` is accepted; registration now requires a name; investment `currentValue` must be non-negative when given.
- Hibernate runs in `validate` mode; SQL echo and security debug logging are no longer always on.
- Frontend reorganised by feature with a shared layer; duplicated formatters, navigation, modals and spinners consolidated; identifiers are strings (UUIDs). Visual design unchanged. After an upload the dashboard now refreshes without blanking the page; uncategorised transactions show "Uncategorized"; a missing date shows "—"; an investment with no recorded value counts as its invested amount.

### Fixed
- Registration page could never succeed (read the response at the wrong level and stored the token under a different key than the route guard reads).
- The "async" import ran on the HTTP request thread (self-invocation bypassed the proxy); it now runs on its own worker bean.
- `tsc -b` (4 errors) and `eslint` (3 errors) failures.
- Components started polling timers that were never cleared on unmount.

### Removed
- Unused/untyped AI gateway stubs (`/risk-score`, `/forecast`, `/recommend` placeholders) and an unused health check method from the uncommitted gateway draft; the Vite template `App.css` and logo; duplicated frontend helpers.
- Duplicate controller-level CORS annotations (one property-driven configuration remains).

### Security
- **Fixed broken object-level authorization**: any signed-in user could read, change or delete another user's transactions, investments and loans by id. All lookups are now scoped to the owner (`OwnershipApiTest`; the test was confirmed to fail when the fix is removed).
- Upload-job status is owner-scoped.
- Refresh tokens are rejected as bearer tokens; invalid/expired tokens yield `401` instead of an unhandled error.
- Removed the committed fallback JWT secret from the base configuration (a development-only key lives in the `dev` profile).
- Password hashes no longer appear in entity `toString()` output.

### Known problems carried into 0.1.0
See [KNOWN_ISSUES.md](KNOWN_ISSUES.md). Notably: no transaction date, duplicate imports, silent row loss, no classification endpoint, Docker stack unverified.

---

## Pre-versioning history (before 2026-10-22, not versioned)

Reconstructed from the Git history of `main`; these were weekly milestones of the original plan.

| Date | Milestone |
|---|---|
| 2026-03-03 – 03-04 | Repository created; README and HTML overview written (describing the *planned* product); scaffold folders |
| 2026-03-05 – 03-06 | Spring Boot backend scaffold; Docker Compose PostgreSQL; application configuration |
| 2026-03-12 – 03-13 | **Week 1:** JWT register/login; React login/register/dashboard shell; `start.ps1` (issue #1–#5) |
| 2026-03-14 | **Week 2:** Transaction, Investment, Loan CRUD APIs with JWT (PR #12, #6) |
| 2026-03-16 | **Week 2:** dashboard shell and list pages with CORS fix (PR #13, #7); add-forms (PR #14, #8) |
| 2026-03-16 – 03-17 | **Week 3:** CSV upload API with asynchronous processing (PR #15, #9); upload UI with drag-and-drop and status polling (PR #16, #10) |

Open GitHub issues #17–#42 (weeks 4–16 of the original plan) are superseded by [ROADMAP.md](ROADMAP.md); the mapping is:
FastAPI/gateway/classification (#17–#19) → v0.5.0; risk (#20–#21) → v0.7.0; forecast (#22–#23) → v0.8.0; recommendations (#24–#25) → v0.10.0;
scenarios (#26–#27) → v0.9.0; dashboard (#28) → v0.6.0; security/validation (#29–#31) → v0.2.0; tests (#32–#33) → continuous;
polish (#34–#35) → v0.11.0; deployment, CI, demo, landing, performance, final testing (#36–#42) → v0.2.0 (CI), v0.11.0, v1.0.0.
