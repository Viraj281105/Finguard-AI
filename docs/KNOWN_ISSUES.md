# Known issues, risks and technical debt

Last reviewed: 2026-10-22, against the refactored working tree (not yet committed).
Severity: **P0** blocks use or compromises data · **P1** blocks the core product experience · **P2** security,
reliability, maintainability · **P3** polish. "Evidence" says how the item is known:
**Confirmed** = observed in code *and* by a test/run or a read of the exact code path; **Suspected** = inferred from
code, never reproduced; **Unverified** = never exercised at all.

There are no open P0 items: the P0 defects found in the Phase 1 audit (cross-user data access, the broken
registration page, the non-asynchronous import, the unusable test database) were fixed and are covered by tests
(see [CHANGELOG.md](CHANGELOG.md)).

## A. Defects in implemented behaviour

| ID | Sev | Issue | Evidence | Fix in |
|---|---|---|---|---|
| KI-001 | P1 | **No transaction date.** The bank's date is stored only inside `note`; the UI "Date" column and all ordering use the import time (`created_at`). Blocks every monthly metric, forecast and recurring-payment feature. | **Resolved in v0.3.0** (`Transaction.date` added; parsed from statement rows; accepted/returned in DTOs; sorted `date DESC, createdAt DESC`; displayed in UI; backfilled safely in Flyway V3) | **Resolved in v0.3.0** |
| KI-002 | P1 | **Duplicate imports.** Uploading the same statement twice stores every row twice; imported rows are not linked to their upload job. | **Resolved in v0.3.0** (Deterministic user-scoped SHA-256 transaction fingerprint; `duplicateRows` counted; `import_job_id` FK links transactions to jobs; `uq_transactions_user_fingerprint` partial unique index guarantees concurrent idempotency) | **Resolved in v0.3.0** |
| KI-003 | P1 | **Fixed column positions.** HDFC/SBI/Axis/Kotak parsers read columns by index (e.g. `cols[4]`); a reordered or extra column silently produces wrong or empty data. | **Resolved in v0.4.0** (`HeaderDrivenStatementParser` dynamically discovers column indices by matching normalized header aliases; tolerates reordered columns, missing optional columns, and extra metadata across all five bank parsers) | **Resolved in v0.4.0** |
| KI-004 | P1 | **Silent row loss.** Unparseable rows are logged and skipped; the job still ends `COMPLETED`, and `totalRows` counts only parsed rows, so the user cannot tell rows were dropped. | **Resolved in v0.4.0** (Full reconciliation metric: `totalRows = rowsProcessed + duplicateRows + rejectedRows + skippedRows`; bounded `rowErrors` recorded with row number, actionable reason, and PII-sanitized snippet; exposed in API and frontend inspector) | **Resolved in v0.4.0** |
| KI-005 | P2 | **ICICI withdrawal/deposit layout unreachable.** Detection requires a `Cr/Dr` column, but the alternate branch only runs without it, so such files are reported as unrecognised. | **Resolved in v0.4.0** (`matchesHeader` and dynamic column role discovery support both single-amount Cr/Dr layout and dual withdrawal/deposit layout for ICICI) | **Resolved in v0.4.0** |
| KI-006 | P2 | **Amount/charset handling is narrow.** Negative numbers, `Dr`/`Cr` suffixes and parenthesised amounts are read as zero (row skipped); non-UTF-8 files are mis-decoded; header detection scans a fixed window. | **Resolved in v0.4.0** (`CsvSupport.parseAmount` supports negatives, parentheses, currency symbols, and `Dr`/`Cr` suffixes; `ImportService` supports UTF-8, UTF-8 BOM, and Windows-1252 fallback decoding; multi-pattern date parser handles ISO, DD/MM/YYYY, DD-MM-YYYY, DD-MMM-YYYY, YYYY-MM-DD) | **Resolved in v0.4.0** |
| KI-007 | P2 | **Interrupted imports stay forever.** A restart while a job is `PENDING`/`PROCESSING` leaves it in that state; the CSV content exists only in memory, so it cannot be resumed. | **Resolved in v0.4.0** (`ImportRecoveryListener` triggers on `ApplicationReadyEvent`, transitions orphaned `PENDING`/`PROCESSING` jobs to `FAILED` with clear re-upload advice, preventing stuck states) | **Resolved in v0.4.0** |
| KI-008 | P1 | **No classification.** The AI service had no `/v1/classify`; every imported transaction had `category = null`, the request carried only the description, the response `confidence` was discarded and there was no provenance. | **Resolved in v0.5.0** (`POST /v1/classify` with a rule-based classifier; request carries description, type, amount; provenance, rule id, version, review flag and calibration flag persisted; user categories protected; V5 migration). Quality on real data is **unknown** — see KI-030. | **Resolved in v0.5.0** |
| KI-009 | P1 | **Transfers, refunds, card payments and accounts are not modelled as relationships.** Since 0.5.0 they can be *labelled* (`TRANSFERS`, `REFUNDS`, `CARD_PAYMENTS`, `INVESTMENTS`, flow ≠ expense) and the client-side totals exclude those labels, but an own-account transfer is still an expense plus an unlinked income, a refund is not netted against its expense, the balance column is ignored and there is no account concept. | Confirmed; partly mitigated by categories | v0.6.0 |
| KI-010 | P1 | **Dashboard arithmetic is client-side**: JavaScript floating point, all-time totals, no month boundaries. (0.5.0 only makes it skip non-spending categories.) | Confirmed (`features/dashboard/summary.ts`) | v0.6.0 |
| KI-011 | P1 | **Core intelligence features do not exist**: risk score, forecast, scenarios, recommendations. No code, endpoint or UI. | Confirmed | v0.7.0–v0.10.0 |
| KI-030 | P1 | **The classifier is hand-written rules; there is no trained model and no measure of real-world quality.** Evaluation data is synthetic and written by the same author as the rules, so the numbers are optimistic. First-look hold-out result: 92.5 % accuracy on 133 synthetic rows (see `ai/evaluation/report.md`); nothing is known about real statements. Real coverage will be lower: payments to people, ATM cash and wallets abstain by design. | Confirmed (`ai/evaluation/report.md`) | When D-06 data exists |
| KI-031 | P2 | **Confidence is not calibrated.** `categoryConfidence` is a rule-strength tier (0.55/0.75/0.90), flagged `categoryConfidenceCalibrated: false`; the UI shows no percentage for it. Calibration needs labelled real data. | Confirmed | With a trained model |
| KI-032 | P2 | **Category coverage gaps by design:** no cash/ATM category (ATM withdrawals stay uncategorised), person-to-person UPI is not guessed, `Food` legacy labels map to `DINING` (could have been groceries), brand lists are finite (582 patterns in `rules-1.0.0`, Indian banks only). | Confirmed (`rules.py`, `report.md`) | Grows with real data |
| KI-033 | P2 | **`AI_INTERNAL_TOKEN` is optional.** With it empty the AI service accepts any caller that can reach it (fine on localhost; Compose publishes port 8000 to the host). Nothing enforces setting it outside development. | Confirmed (`core/security.py`, `AiConfig`) | v1.0.0 (deployment) |
| KI-034 | P3 | **Classification is synchronous.** During import it adds up to 90 s in the worst case (a hanging service; a dead one costs ≈ 2 × the 5 s connect timeout); `POST /api/transactions/reclassify` is synchronous and limited to 500 rows per call. | Confirmed (`AiClient`, `CategorizationService`) | When imports grow |
| KI-035 | P3 | **The live Java ↔ Python tests need a Python environment.** They are skipped (reported as skipped by JUnit) when neither `FINGUARD_AI_PYTHON` nor `ai/.venv` exists; CI sets `FINGUARD_REQUIRE_LIVE_AI=true` so they cannot be skipped there. The CI change itself has not been run (no CI run was performed). | Confirmed | First CI run |
| KI-036 | P3 | **`PUT /api/transactions/{id}` semantics changed in 0.5.0:** an absent `category` keeps the current one (it used to clear it); `""` clears. Clients that relied on clearing by omission now send `""`. | Documented in API.md | — |
| KI-012 | P2 | **Session handling is basic.** Access tokens live 24 h; `/api/auth/refresh` exists but the web app never calls it; no logout/revocation; refresh tokens are not invalidated on use; the token is kept in `localStorage`. | **Resolved in v0.2.0** (15m access, 7d refresh rotation, reuse detection revoking family, server-side revocation table, HttpOnly SameSite=Strict cookie, logout endpoint, frontend silent refresh with in-flight merging) | **Resolved in v0.2.0** |
| KI-013 | P2 | **No brute-force protection.** No rate limiting or lockout on `/api/auth/**`; no security headers. | **Resolved in v0.2.0** (In-memory IP + email sliding rate limiter returning 429 and `Retry-After: 60`; security headers `nosniff`, `DENY` frame-options, referrer policy, CSP configured) | **Resolved in v0.2.0** |
| KI-014 | P2 | **Framework errors may become 500.** The catch-all `@ExceptionHandler(Exception.class)` also catches Spring's own "no such route / method not allowed / unsupported media type" exceptions. | **Resolved in v0.2.0** (`GlobalExceptionHandler` handles 404 `NoResourceFoundException`, 405 `HttpRequestMethodNotSupportedException`, 415 `HttpMediaTypeNotSupportedException`, 406 `HttpMediaTypeNotAcceptableException` returning uniform `ApiError`) | **Resolved in v0.2.0** |
| KI-015 | P2 | **Weak account rules.** Email addresses are case-sensitive (`A@x.com` and `a@x.com` can both register); minimum password length is 6. | **Resolved in v0.2.0** (Email lowercase trimmed normalization in Java and DB; Flyway V2 migration enforcing uniqueness via `uq_users_lower_email`; minimum password length raised to 10) | **Resolved in v0.2.0** |
| KI-016 | P2 | **No pagination, no indexes.** List endpoints return every row; only primary keys and `users.email` are indexed (foreign keys are not). | **Resolved in v0.3.0** (Backward-compatible pagination on `GET /api/transactions` with `X-Total-Count`, `X-Page`, `X-Page-Size`, `X-Total-Pages` headers; composite index `idx_transactions_user_date`, `uq_transactions_user_fingerprint`, and foreign key indexes on `upload_jobs`, `investments`, `loans`) | **Resolved in v0.3.0** |
| KI-026 | P3 | **Interest rate precision.** `loans.interest_rate` is `numeric(38,2)`; `8.755` would be stored as `8.76`. | **Resolved in v0.3.0** (Widened to `numeric(10,4)` in Flyway V3 and `Loan` entity, verified by `LegacyDatabaseAdoptionTest`) | **Resolved in v0.3.0** |

## B. Unverified (code or configuration exists, never run)

| ID | Sev | Item | Why unverified | Resolve in |
|---|---|---|---|---|
| KI-018 | P2 | `docker-compose.yml` services (PostgreSQL 15 on 5433, `ai`), the `ai/Dockerfile` build (Python 3.12), `start.ps1` | Docker Desktop daemon not running on the development machine. `docker compose config` (syntax) passes. | Gate for v0.1.0 |
| KI-019 | P2 | Parsers against **real** bank exports | Verified against synthetic fixtures and permutations (reordering, missing optional columns, multiple date/amount formats, BOM, Windows-1252); compatibility with proprietary bank exports directly from net banking portals remains unverified pending real anonymized samples from the owner. | Ongoing (v0.4.0+) |
| KI-029 | P2 | HTTP 413 (file over 5 MB) and 503 (import queue full) paths | **Resolved in v0.4.0** (413 verified for uploads > 5MB throwing `MaxUploadSizeExceededException`; 503 verified for `TaskRejectedException` queue saturation via `UploadErrorApiTest`). | **Resolved in v0.4.0** |
| KI-017 | P2 | Backend tests on a CI runner (embedded PostgreSQL on Linux) | **Resolved in v0.2.0** (`.github/workflows/ci.yml` added running backend, frontend, and AI test suites). | **Resolved in v0.2.0** |
| KI-025 | P2 | Python 3.14 (local) vs 3.12 (Docker): whether scikit-learn/numpy wheels exist for 3.14. | **Closed for 0.5.0 by not needing them**: the service depends only on FastAPI/pydantic and runs the rule classifier; 121 tests pass on the local 3.14 interpreter. The question **returns with the first ML dependency** (KI-030). The Docker image (3.12) is still unbuilt (KI-018). | **Closed in v0.5.0** |
| KI-028 | P2 | **Forgetting `SPRING_PROFILES_ACTIVE=prod`** runs production with the development JWT key and DB defaults. | **Resolved in v0.2.0** (`SecurityEnvironmentGuard` `ApplicationContextInitializer` halts startup if default/dev JWT secret is used with non-local database host). | **Resolved in v0.2.0** |
| — | P3 | Frontend in a real browser: layout, responsiveness, accessibility, drag-and-drop, theme | Tests run in jsdom only | v0.11.0 |

## C. Technical debt and housekeeping

| ID | Sev | Item | Fix in |
|---|---|---|---|
| KI-020 | P3 | `README.html` is a 56 KB marketing page describing the *planned* product (91 % accuracy, Prophet, Nginx …). A banner now warns readers; retire or regenerate it. | v1.0.0 |
| KI-021 | P3 | 28 tracked `.gitkeep` placeholders and legacy empty folders (`frontend/src/{components,hooks,pages,services,store,types,utils}`, `ai/app/{models,routers,schemas,utils}`, `ai/models-cache`). | **Resolved in 0.1.0** |
| KI-022 | P3 | Component versions aligned to `0.1.0` across backend, frontend, and AI. | **Resolved in 0.1.0** |
| KI-023 | P3 | `npm audit` reports vulnerabilities (triaged: 24 findings mostly in build tooling and router patch advisory; dependency updates planned for v0.2.0). | **Resolved in v0.2.0** (`npm audit fix` reduced vulnerabilities from 24 to 7 without breaking changes; remaining 7 tied to Tailwind v3 / PostCSS parser awaiting Tailwind v4 migration) |
| KI-024 | P3 | Test warnings: Mockito inline mock maker self-attaching (will break on a future JDK); Spring Security notice about a custom `AuthenticationProvider`. | **Resolved in v0.2.0** (Surefire `argLine` sets `-XX:+EnableDynamicAgentLoading`; redundant `DaoAuthenticationProvider` bean removed) |
| KI-027 | P3 | No coverage tooling; no lint for Java; ArchUnit rules cover only existing modules. | **Resolved in v0.2.0** (JaCoCo 0.8.12 configured for backend; `@vitest/coverage-v8` configured for frontend) |
| KI-030 | P3 | Frontend: amounts typed with `parseFloat`; no pagination UI; no accessibility audit; every page ships in one JS bundle (≈ 309 kB, 98 kB gzip). | v0.3.0 / v0.11.0 |
| KI-031 | P3 | Original planning documents (`Documentation/*.docx`) live outside the repository, unversioned. Not read in full; not authoritative. | decide: import or archive |
| KI-032 | P3 | Docker image for the backend and frontend do not exist; only PostgreSQL and the AI service are containerised. | v0.11.0 |

## D. Blockers and external dependencies

| Blocker | Affects | Needed from |
|---|---|---|
| Docker Desktop running | Verifying KI-018; releasing v0.1.0 | The developer machine |
| **Anonymised real bank statements** (all five banks) | v0.4.0 parser confidence, v0.5.0 classifier training/evaluation | The project owner |
| Labelled transaction categories | A trained model; any statement about real-world accuracy or calibrated confidence | Owner / own data |
| Open decisions D-01…D-14 in [DECISIONS.md](DECISIONS.md) part B | v0.1.0 (D-12), v0.2.0 (D-01…05), v0.3.0 (D-07), v0.4.0–0.5.0 (D-06, D-14), v0.6.0 (D-08, D-10), v0.7.0 (D-09), v0.8.0 (D-13), v1.0.0 (D-11) | The project owner |
| Authorisation to commit/push | Nothing is committed; all refactoring and documentation are uncommitted working-tree changes | The project owner |

## E. Not issues (deliberately out of scope until 1.0)

Multi-currency, bank-API/aggregator integrations, PDF/OCR statements, account deletion, admin roles, mobile apps,
LLM-generated advice, real-time stock prices.
