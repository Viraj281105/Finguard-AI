# Release plan

Version-by-version scope, requirements and acceptance criteria. Overview, ordering and dependencies are in
[ROADMAP.md](ROADMAP.md); how versions and tags work is in [VERSIONING.md](VERSIONING.md). Issue ids (`KI-…`) are in
[KNOWN_ISSUES.md](KNOWN_ISSUES.md); decision ids (`D-…`) in [DECISIONS.md](DECISIONS.md).

**Everything below v0.1.0 is Planned.** None of it exists. Effort is relative (S/M/L/XL); no calendar estimates are
given because none can be justified from the evidence so far.

## Release gate (applies to every version)

A version is releasable only when **all** hold:

1. `.\scripts\test-all.ps1` is green on a clean checkout (and CI is green, once CI exists — v0.2.0).
2. Every acceptance criterion of the version is met **and demonstrated** by a test or a recorded manual check.
3. New/changed endpoints are in [API.md](API.md); schema changes in [DATABASE.md](DATABASE.md) with a data-bearing migration test;
   the version is in [CHANGELOG.md](CHANGELOG.md); [PROGRESS.md](PROGRESS.md), [KNOWN_ISSUES.md](KNOWN_ISSUES.md) and [TESTING.md](TESTING.md) are current.
4. No planned feature is described as existing anywhere; no unverified claim (accuracy, performance) is published.
5. No open P0/P1 issue introduced by the version. Remaining known issues are listed with a target version.
6. Component versions equal the product version; the release commit is tagged `vX.Y.Z` ([VERSIONING.md](VERSIONING.md)).

---

## v0.1.0 — Foundation (refactor baseline)

**Status:** implemented in the working tree, **uncommitted**; release blocked on D-12 / KI-018.

**Goal:** a clean, modular, tested base to build on, with behaviour preserved.

**Scope (done):** domain-based backend modules with ArchUnit-enforced boundaries; Flyway baseline `V1` and
`ddl-auto=validate`; `dev`/`prod` profiles and environment-driven configuration; ownership-scoped repositories (404 for
foreign records); uniform error body; JWT with token types and a refresh endpoint; corrected registration flow;
non-blocking, atomic statement import with a bounded worker pool; per-bank parser classes; typed, batched, failure-tolerant `AiClient`;
feature-organised frontend with a shared API client and tests; Python service skeleton (`/health`); Compose file,
`.env.example`, `start.ps1`, `scripts/test-all.ps1`; full documentation set.
**Not in scope:** any schema change, new endpoint other than `/auth/refresh`, new financial feature.

**Remaining before the tag**
- Start Docker Desktop and verify: `docker compose up -d --wait postgres ai` becomes healthy; the backend (dev profile) boots against it; `start.ps1` launches the stack; register → import a fixture CSV → see transactions in the browser. Record the result in [PROGRESS.md](PROGRESS.md).
- Align component versions to `0.1.0` (`backend/pom.xml`, `frontend/package.json`).
- Owner commits and tags (nothing has been committed by the tooling).

**Acceptance criteria**
- [ ] All suites green (met on 2026-10-22: backend 70, frontend 49, AI 3).
- [ ] Docker stack and manual flow verified as above (**not met**).
- [ ] Documentation consistent with the code (met at documentation time; re-check at release).

**Risks:** Compose/`start.ps1`/AI image may need small fixes (KI-018).

---

## v0.2.0 — Session security & delivery hygiene

**Goal:** close the remaining authentication weaknesses and put an automated safety net in place before feature work.
**Effort:** M. **Depends on:** v0.1.0 tag, and owner answers to D-01…D-05. Independent of v0.3.0 — can run in parallel.

**Scope**
- Session model per D-01/D-02/D-03: short-lived access token; refresh with rotation; frontend silent refresh (single in-flight refresh, retry the failed request once, then log out); a real `POST /api/auth/logout`; if D-03(b), a table of hashed refresh-token ids with reuse detection.
- Rate limiting / temporary lock on `/api/auth/login`, `/register`, `/refresh` (D-04).
- Account rules: lower-case email normalisation with a migration and case-insensitive uniqueness (D-05); password minimum raised (D-02).
- Error contract: map framework exceptions correctly (unknown route 404, method 405, media type 415, 413, with the standard body) — KI-014, KI-029.
- Guard against running with dev defaults outside localhost (KI-028): startup warning/refusal when the dev JWT key is in use with a non-local datasource.
- Standard security headers on API responses.
- **CI**: GitHub Actions workflow running backend tests, frontend typecheck/lint/tests/build, AI tests and lint on every PR; dependency audit step; fix or document each `npm audit` finding (KI-023, KI-017).
- Quality tooling: JaCoCo and Vitest coverage *reports* (no threshold until measured), resolve the Mockito agent warning (KI-024, KI-027).
- Housekeeping: `git rm` the placeholder `.gitkeep` files and empty legacy folders (KI-021).

**Modules affected:** `identity`, `common`, `frontend/shared/{api,auth}` and the auth feature, `.github/workflows`, `pom.xml`, `package.json`.
**Migrations:** possibly `V2` for refresh tokens and `lower(email)` index; the email migration must stop with a clear message if case-insensitive duplicates exist.

**Security requirements:** no token or password in logs; refresh tokens stored only hashed; login failure responses indistinguishable for unknown user and wrong password (already true — keep); limits are per client and per account; logout invalidates the refresh token server-side (if D-03(b)).
**Financial-correctness requirements:** none.

**Tests & verification**
- Auth lifecycle: rotation issues a new refresh token and the old one is rejected (D-03b); reuse of a rotated token revokes the family; expired access + valid refresh recovers; logout then refresh fails.
- Rate limit: N+1th attempt is rejected with `429` and `Retry-After`; counters are per key.
- Email: `A@x.com` then `a@x.com` is a conflict; migration test with mixed-case legacy rows.
- Handler tests for 404/405/415/413.
- Frontend: refresh interceptor (parallel 401s cause one refresh; failed refresh logs out), logout button.
- CI workflow proven by a green run on a PR.

**Acceptance criteria**
- [ ] A stolen/old refresh token cannot be replayed after logout or rotation (if D-03b chosen).
- [ ] 6 rapid wrong-password logins are throttled; legitimate login still works afterwards.
- [ ] Unknown route → 404, wrong method → 405, wrong content type → 415, all with the standard error body.
- [ ] Case-variant duplicate emails cannot be created; migration verified on a database with rows.
- [ ] CI runs all suites on a PR and is green; coverage reports are produced.
- [ ] KI-012, 013, 014, 015, 017, 021, 023, 024, 027, 028, 029 (413 part) closed or re-ticketed with reasons.

**Risks:** cookie-based refresh adds CSRF considerations (mitigate: `SameSite=Strict`, refresh endpoint accepts only its own cookie and a custom header); rate-limit state is in-process, so it holds for a single instance only (document); the embedded PostgreSQL binaries must work on the CI runner (KI-017).
**Done when:** all criteria checked, docs/changelog updated, tag `v0.2.0`.

---

## v0.3.0 — Dated, idempotent ledger

**Goal:** every transaction has its real calendar date and provenance, and importing a statement twice changes nothing.
**Effort:** L. **Depends on:** v0.1.0. (Parallel with v0.2.0; migration numbers are assigned in merge order.) Decision D-07.

**Scope**
- Migration adding to `transactions`: `txn_date date` (back-filled: from the `Imported from X | yyyy-MM-dd` note where present, else `created_at::date`; then `NOT NULL`), `source` (`MANUAL`/`IMPORT`), `import_job_id` (FK to `upload_jobs`, nullable), `fingerprint` (nullable), category provenance (`category_source` `NONE|RULE|MODEL|USER`, `category_confidence`, `category_model_version`); unique index on `(user_id, fingerprint)`; index on `(user_id, txn_date)`; wider `loans.interest_rate` (KI-026).
- **Fingerprint rule** (fixed in `FINANCIAL_RULES.md` before coding): SHA-256 over user id, `txn_date`, direction, amount (scale 2), normalised description (trimmed, case-folded, whitespace-collapsed) and an *occurrence index* counting identical rows within the same file, so two genuinely identical same-day rows both import but a re-upload adds nothing.
- Import writes `txn_date`, `source=IMPORT`, `import_job_id`, fingerprint; skips duplicates; the job reports `importedRows` and `duplicateRows`.
- API (additive, backward compatible): transactions accept optional `date` (default: today), responses add `date`, `source`, `categorySource`, `categoryConfidence`; list supports `page`, `size`, `from`, `to`, `type`, `category` and orders by `date` then `createdAt` descending. Update `API.md`.
- Frontend: date column and ordering use `date`; manual add form gets a date field; import result shows imported/duplicate counts; filters and pagination controls; amounts typed as strings or decimal-safe input.

**Modules affected:** `ledger` (domain/service/web), `ingestion` (worker, job), migration, `frontend/features/{transactions,statement-import}`.
**Security:** list filters are applied inside the owner-scoped query; fingerprints include the user id so equal rows of different users never collide; no raw descriptions in logs.
**Financial correctness:** dates are `LocalDate` without time zone; amounts stay `BigDecimal`; back-fill is deterministic and idempotent; the fingerprint is stable across re-parses.

**Tests & verification**
- Migration on a database holding legacy rows: dates back-filled, all rows retained, constraints present (extend `LegacyDatabaseAdoptionTest`).
- Upload the same fixture twice → second job `COMPLETED` with 0 imported / N duplicates; transaction count unchanged; two identical rows in one file both import.
- Same file uploaded by two users → both import (no cross-user dedupe).
- Pagination/filter/order tests; cross-user checks for the new query parameters in `OwnershipApiTest`.
- Fingerprint unit tests (normalisation, determinism, differs by amount/date/direction).

**Acceptance criteria**
- [ ] The date shown in the UI is the bank's transaction date for imported rows.
- [ ] Re-uploading any fixture adds zero rows and reports the duplicate count.
- [ ] Existing clients that omit `date` still work (compatibility test).
- [ ] Lists are paginated; default page size documented; unfiltered behaviour unchanged for small data.
- [ ] KI-001, 002, 016, 026 closed.

**Risks:** overlapping statements with identical same-day rows can mis-count the occurrence index (document the rule and test the overlap case); back-fill from free text is best-effort (keep `created_at` fallback and mark which rows were back-filled in the changelog).
**Done when:** criteria checked, migration documented, tag `v0.3.0`.

---

## v0.4.0 — Reliable statement import

**Goal:** a user can tell exactly what was imported, what was skipped and why; parsers tolerate real-world variation.
**Effort:** L. **Depends on:** v0.3.0 (dates, import-job link). **Needs:** D-06 (real anonymised statements) for confidence.

**Scope**
- Header-driven column mapping for all five banks (match columns by header text; tolerate reordering and extra columns); remove positional indexing (KI-003); make the ICICI withdrawal/deposit layout reachable (KI-005).
- Multiple accepted date formats per bank; amount parsing for negatives, `Dr`/`Cr` suffixes, parentheses, currency symbols (KI-006); charset detection with a Windows-1252 fallback; row and size limits; reject non-text content.
- **Import report:** every skipped row recorded with line number and reason (a bounded list, no full-row echo of sensitive text beyond a short excerpt), summary counts (`parsed`, `imported`, `duplicates`, `skipped`), returned by the status endpoint and shown in the UI (KI-004). Import history endpoint `GET /api/upload/jobs`.
- Capture the statement's closing balance and period when present (inputs for v0.7.0 liquidity — D-09).
- Recovery: on startup, jobs left `PENDING`/`PROCESSING` become `FAILED` with "interrupted — please re-upload" (safe because import is idempotent after v0.3.0) (KI-007).
- Tests for 503 queue-full and 413 oversize (KI-029).

**Modules affected:** `ingestion` (parser, worker, job entity, web), migration for the report/balance columns, `frontend/features/statement-import`.
**Security:** content validation before parsing; limits protect memory (file is read fully today — document the cap); error excerpts truncated; the report is only visible to the job's owner (ownership test).
**Financial correctness:** parsing never invents values: ambiguous rows are skipped *and reported*; debit/credit sign rules documented per bank; totals reconcile (`parsed = imported + duplicates`; `file data rows = parsed + skipped`).

**Tests & verification**
- Per bank: reordered columns, extra columns, each supported date format, negative/`Dr`/`Cr`/parenthesised amounts, quoted commas, BOM, Windows-1252.
- Report: a fixture with known bad rows yields exactly those line numbers and reasons.
- **Real-statement regression tests** (anonymised, committed) for each bank if D-06 is satisfied; otherwise this is recorded as unmet and the parsers remain "synthetic-verified".
- Restart-recovery test; oversize and queue-full tests; cross-user check on the report/history.

**Acceptance criteria**
- [ ] No row can disappear silently: counts reconcile for every fixture.
- [ ] Reordering the columns of each fixture does not change the result.
- [ ] The UI shows imported / duplicate / skipped counts and the reasons for skipped rows.
- [ ] An interrupted job is reported as failed after restart.
- [ ] KI-003…007, 019 (if real statements supplied), 029 closed.

**Risks:** real exports will differ from assumptions (mitigate by obtaining samples early); header synonyms vary by bank and year; balance column semantics differ.
**Done when:** criteria checked, parser matrix (bank × supported variations) documented in `API.md`/`FINANCIAL_RULES.md`, tag `v0.4.0`.

---

## v0.5.0 — Transaction classification

**Goal:** imported transactions get a trustworthy category and provenance; the user can review and correct them.
**Effort:** XL. **Depends on:** v0.3.0 (provenance columns), v0.4.0 (reliable text). The Python skeleton/contract work can start in parallel with v0.4.0. **Needs:** D-06, D-14.

**Scope — AI service (Python)**
- `POST /v1/classify` implementing the contract in [API.md](API.md#internal-contract-backend--ai-service): a **rule layer** (merchant/keyword/UPI-pattern dictionary for Indian transactions) followed by a **text model** (TF-IDF + linear classifier, scikit-learn) for the remainder; each result carries `category`, `confidence`, `source` (`RULE`/`MODEL`) and `model_version`. Unknown → no category (never a forced guess below the confidence threshold).
- Versioned labelled seed data and a deterministic, seeded training script; `evaluate` script writing a report (accuracy, macro-F1, per-class results, confusion matrix) on a **held-out split**; the report is the only source for any accuracy statement. The seed data's origin is documented; if it is synthetic, the report says so.
- OpenAPI contract exported to `contracts/` and checked in CI; service-to-service shared-secret header (`X-Internal-Token`) validated by Python and sent by Java; `/health` lists `capabilities: ["classify"]` only once true.
**Scope — backend & frontend**
- Persist `category_source`, `confidence`, `model_version`; **never overwrite a `USER` category**; richer request (description + amount + direction).
- `PATCH /api/transactions/{id}/category` (user override), a re-classify action for uncategorised rows, listing filter "uncategorised".
- UI: category badge with source and confidence, inline edit, review queue for low-confidence rows.

**Modules affected:** `ai/` (new `classification`, `training`), `ai` and `ingestion`/`ledger` in Java, contract files, frontend transactions feature.
**Security:** the AI service accepts requests only with the shared secret; it stores nothing and logs no descriptions; request size capped.
**Financial correctness:** classification never changes amounts or direction; user categories are authoritative.

**Tests & verification**
- Python: rule table tests; deterministic training (same seed → same artefact hash); API tests; held-out evaluation reproduced by one command; latency measured on a batch of 200 and reported (not assumed).
- Contract: Java `AiClient` tested against a real running FastAPI instance in CI (and against recorded examples offline).
- Java: provenance persisted; USER override survives re-import/re-classify; AI down → import still completes, rows stay `NONE`.
- Frontend: edit category, filter uncategorised, source/confidence rendering.

**Acceptance criteria**
- [ ] `capabilities` in `/health` equals the implemented endpoints, and the existing guard test is updated accordingly.
- [ ] The evaluation report is generated by a documented command; any accuracy figure in the docs equals the report and names the data it was measured on.
- [ ] A user-set category is never replaced by automation (test).
- [ ] An import with the AI service stopped completes with uncategorised rows (test).
- [ ] KI-008, 025 closed.

**Risks:** no real labelled data (D-06) means measured accuracy reflects the seed data only; Python 3.14 vs 3.12 wheel availability (use the 3.12 image/venv); model drift as merchants change; dependency size (keep scikit-learn only; no embeddings/FAISS/pgvector in this release).
**Done when:** criteria checked; evaluation report committed; tag `v0.5.0`.

**Delivery status (2026-10-22, uncommitted, untagged).** Delivered as specified: `POST /v1/classify`, provenance
persistence, `PATCH …/category`, re-classify, "uncategorised" and "needs review" filters, UI badge/edit/review queue,
shared secret, evaluation script and report, `capabilities` guard test, user-category protection and AI-down tests,
real-process Java ↔ Python contract test. **Deliberately different from the plan:** (1) the "text model" (TF-IDF +
linear classifier) was **not built** — there is no real labelled data (D-06), so a trained model would only measure the
author's own synthetic text; the rule layer ships alone behind a `Classifier` interface that a model can join
(decision A-13); (2) no separate training script or artefact hash, for the same reason; (3) the OpenAPI export to
`contracts/` was replaced by a recorded request/response pair (`classify-examples.json`) verified on both sides, and
the taxonomy contract (`categories.json`); (4) the taxonomy has 19 ids (A-12). Acceptance criteria: capabilities
guard ✅ · evaluation command documented and the only source of figures ✅ (synthetic, labelled as such) · user
category never replaced ✅ · import with AI down completes ✅ · KI-008 ✅ and KI-025 ✅ closed.

---

## v0.6.0 — Financial semantics & analytics

**Goal:** correct, server-computed figures with explicit definitions, replacing the browser arithmetic.
**Effort:** L. **Depends on:** v0.3.0, v0.4.0, v0.5.0. Decisions D-08, D-10.

**Scope**
- Transaction **flow kind** (income, expense, transfer, refund, card payment, investment contribution): rule-based suggestion at import, user confirmation/override, stored with provenance; transfers and card-bill payments are excluded from income/expense totals, refunds reduce the related expense category (rules written in `FINANCIAL_RULES.md` first) (KI-009).
- New `intelligence` module with deterministic analytics (`BigDecimal`, scale 2, calendar months of `txn_date`): monthly income/expense/net/savings rate, category breakdown, EMI burden from loans, investment summary, **recurring-payment detection** (same payee and similar amount at a monthly cadence over ≥ 3 months), and a **data-quality block** (months covered, first/last date, uncategorised share, gaps).
- Endpoints (no version prefix, D-07): `GET /api/analytics/summary`, `/monthly`, `/categories`, `/recurring`, `/data-quality`, each accepting `from`/`to`.
- Frontend: dashboard reads the server summary; `computeSummary` and client arithmetic removed (KI-010); first charts (monthly bars, category breakdown) using the library chosen in D-08; empty / insufficient-data states.

**Modules affected:** `ledger` (flow kind), new `intelligence`, `frontend/features/dashboard` + chart components.
**Security:** all analytics queries owner-scoped; ranges validated (`from ≤ to`, bounded span).
**Financial correctness:** every response states its exclusions (for example `{"excludes": ["TRANSFER","CARD_PAYMENT"]}`); sums reconcile (`income − expense = net`); no float arithmetic anywhere; results independent of insertion order.

**Tests & verification**
- Golden datasets whose expected results are computed by hand and written into the tests (month boundaries on the 1st/last day, leap year, refunds, transfers, zero data, one month of data).
- Property tests: totals invariant under row reordering; income+expense reconcile to net; splitting a month in two ranges sums to the whole.
- Recurring detection tests (true positives, near-miss amounts, two-month sequences rejected).
- Frontend: charts render empty/partial data; dashboard shows server values only.

**Acceptance criteria**
- [x] Dashboard figures equal the API's figures and match an independent hand calculation on the golden dataset.
- [x] A transfer between two own accounts changes neither income nor expense.
- [x] Insufficient history yields an explicit "insufficient data" response, not zeros presented as results.
- [x] No money arithmetic remains in the frontend (grep-able check in a test or lint rule).
- [x] KI-009, 010 closed.

**Risks:** transfer/refund detection will be imperfect — hence suggestion + override; recurring detection needs enough history; chart library adds bundle weight (measure).
**Done when:** criteria checked, definitions documented, tag `v0.6.0`.

---

## v0.7.0 — Budgeting & Financial Goals

**Goal:** a production-quality budgeting and financial goal-tracking experience on top of the ledger, categorisation system, and analytics engine.
**Effort:** L. **Depends on:** v0.6.0.

**Scope**
- **Monthly budgeting engine (`com.finguard.backend.budgeting`):**
  - Overall monthly spending budget and per-category monthly spending budgets.
  - CRUD operations (`POST`, `GET`, `PUT`, `DELETE /api/budgets`) with duplicate prevention (409 Conflict) and database unique partial indexes.
  - Month boundaries: supports past, current, and future months (`yearMonth = YYYY-MM`).
  - Performance tracking (`GET /api/budgets/overview`): compares actual expenditure with budgets, calculates percentage used and remaining amounts, separates unbudgeted spending, and returns warnings for approaching/exceeding limits.
  - Thresholds: Under budget (< 80%), approaching limit (80%–100%), over budget (> 100%).
  - Financial correctness: Debits in expense categories minus refunds; non-spending flows (`TRANSFERS`, `CARD_PAYMENTS`, `INVESTMENTS`) strictly excluded.
- **Financial goals system:**
  - Create, view, update, archive, and delete savings goals (`/api/goals`).
  - Concurrency-safe contribution tracking (`POST /api/goals/{id}/contributions`) with pessimistic row locking (`@Lock(PESSIMISTIC_WRITE)`).
  - Target date pacing: calculates required monthly contribution pace for future target dates and flags overdue uncompleted goals.
  - Automatic status transitions: `ACTIVE` → `COMPLETED` when `currentAmount >= targetAmount`; reverts if contributions are deleted.
  - **Banking disclaimer:** Goal contributions are explicitly designated as self-tracked allocations for planning purposes, not verified bank balances or automated bank transfers.
- **Frontend experience:**
  - Dedicated `BudgetsGoalsPage` with dual tabs ("Monthly Budgets" and "Financial Goals").
  - Month selector with formatted labels and quick jump to current month.
  - Overall budget summary card, category progress bars with status badges, and contextual warnings.
  - Goal cards with progress rings/bars, remaining days, monthly pacing, and contribution modal with history inspector.
  - Dashboard integration card `BudgetHealthPreviewCard` showing monthly budget utilization and top goal status.
- **Database & Architecture:**
  - Flyway migration `V6__budgeting_and_financial_goals.sql` (`budgets`, `financial_goals`, `goal_contributions`).
  - 11 ArchUnit rules enforcing strict modular boundaries.

**Acceptance criteria**
- [x] Budgets and financial goals persist correctly with Flyway V6 migrations.
- [x] Financial calculations follow documented, consistent rules (refund offsets, non-spending exclusions, scale-2 `BigDecimal`).
- [x] User ownership and data isolation are enforced across all endpoints.
- [x] The UI is polished, responsive, and connected to real backend data.
- [x] Goal contributions are clearly distinguished from verified bank savings with explicit disclaimers.
- [x] Existing analytics, imports, categorisation, and transaction management continue working without regressions.
- [x] All 406 automated tests (197 backend, 88 frontend, 121 AI) pass cleanly.
- [x] Component versions aligned to 0.7.0 across backend, frontend, and AI services.

**Delivery status (2026-10-24, uncommitted, untagged).** Fully implemented, verified against backend, frontend, and AI test suites. Working tree uncommitted.

---

## v0.8.0 — Cash-flow forecast

**Goal:** a 3/6/12-month projection with uncertainty bands, honest about limited history.
**Effort:** L. **Depends on:** v0.6.0 (monthly series, exclusions), the Python service from v0.5.0. Decision D-13.

**Scope**
- `POST /v1/forecast` in Python: input monthly series plus horizon; baseline method per D-13 (trend + seasonal-naive/rolling mean) with **empirical prediction intervals** from residuals; response includes `method`, `history_months`, `warnings`; refuses (422 with reason) below the documented minimum history.
- Java orchestration: builds the series from analytics (excluding transfers/one-offs per v0.6.0 rules), adds **known fixed obligations** (active loan EMIs, detected recurring payments) deterministically, calls the service, returns `GET /api/forecast?months=3|6|12`.
- Backtesting harness (rolling origin) reporting error on the user's own history and on the golden datasets; results are shown to the user as "how this method would have done", not as a guarantee.
- Frontend: forecast chart with bands, horizon toggle, insufficient-data and AI-unavailable states.

**Security:** same shared-secret and statelessness rules as v0.5.0. **Financial correctness:** all aggregation and obligation maths in Java; Python receives numbers and returns numbers only; intervals are labelled with their meaning (for example "80 % of past monthly errors fell within this band").

**Tests & verification:** deterministic outputs on fixed series; interval coverage measured on synthetic series with known noise; minimum-history refusal; Java behaviour when Python is down (clear error state, no fabricated forecast); chart rendering of bands and empty state.

**Acceptance criteria**
- [ ] Same series ⇒ same forecast (tested).
- [ ] < minimum history ⇒ explicit refusal shown in the UI.
- [ ] Python outage ⇒ the page shows an error state; no stale or invented numbers.
- [ ] Documented, reproducible backtest command; no accuracy claim beyond its output.
- [ ] KI-011 (forecast part) closed.

**Risks:** short, irregular personal histories give wide, weakly informative bands (state this in the UI); a heavier model (ETS/Prophet) is deliberately deferred.
**Done when:** criteria checked, tag `v0.8.0`.

**Delivery status (uncommitted, untagged) — and how it differs from the plan above.** Delivered as **statistical forecasting in the Java
backend** (decision A-16), not a Python `POST /v1/forecast`: no AI-service dependency, so there is no "Python outage" state.
Scope delivered beyond the plan: category-level forecasts, month-end budget outlook, savings projection with a
projected-surplus / goal-contribution / bank-balance separation, and a what-if scenario simulator (all from the original v0.8 ask; the
plan's v0.9.0 "simulation" is therefore partly advanced: cash-flow scenarios exist, loan/investment scenarios do not). Intervals are
**empirical past-error bands shown only with ≥ 8 samples**, not 80 % probability bands. Backtesting: chronological rolling origin per user
(`GET /api/forecast/backtest`) plus a reproducible synthetic evaluation (`SyntheticEvaluationTest`). Acceptance: same series ⇒ same forecast ✅ ·
< 3 months ⇒ explicit refusal in the UI ✅ · reproducible backtest command, no accuracy claim beyond its output ✅ (and the claim is
"synthetic only") · KI-011 forecast part ✅ · "Python outage" criterion: not applicable by design.

---

## v0.9.0 — Scenario simulator

**Goal:** let users change a decision and see how projected cash flow and risk change, and why. This is the distinguishing feature.
**Effort:** XL. **Depends on:** v0.7.0, v0.8.0.

**Scope**
- Scenario = named list of adjustments to a baseline: close or prepay a loan, change an EMI, start/increase/stop an investment contribution, change spending in a category by an amount or percentage, one-time income/expense, income change.
- `intelligence`/`simulation` engine: applies adjustments to the baseline monthly series and loan/investment state (EMI/amortisation maths in `BigDecimal` with reference tests), re-runs the forecast and the risk model, and returns **baseline vs scenario** with per-dimension deltas and per-month cash-flow differences and a plain-language "what changed and why" derived from the numbers.
- `POST /api/scenarios/run` (ephemeral), `POST/GET/DELETE /api/scenarios` (saved scenarios with the adjustments, results snapshot and the model versions).
- Frontend: scenario builder, side-by-side comparison, saved scenarios list.

**Security:** owner-scoped storage; adjustment inputs validated and bounded. **Financial correctness:** *identity property* — a scenario with no adjustments reproduces the baseline exactly; deltas are differences of two deterministic runs, never estimated separately; amortisation matches published reference schedules; results record the model versions used.

**Tests & verification:** identity property; amortisation vs reference tables; closing a loan lowers debt-burden risk and EMI outflow by exactly the EMI; monotonic checks; saved scenario reproduces when re-run on unchanged data; end-to-end API test.

**Acceptance criteria**
- [ ] Identity property passes on every golden dataset.
- [ ] Each reported delta is explained by named inputs in the response and the UI.
- [ ] A saved scenario can be re-run and yields the same result while data is unchanged.
- [ ] Missing history makes the scenario unavailable with a reason (not an estimate).
- [ ] KI-011 (scenario part) closed.

**Risks:** compound uncertainty (forecast × adjustment) must be communicated; interest/prepayment rules vary by lender (document the simplifications explicitly).
**Done when:** criteria checked, tag `v0.9.0`.

**Delivery status (uncommitted, untagged).** Delivered in the Java backend on top of the v0.8.0 simulator (no separate `simulation` engine). Scope delivered: close / prepay / change-EMI
loan adjustments with `BigDecimal` amortisation checked against reference values, one-time income / expense items, income, category and recurring adjustments (v0.8.0), `loanEffects`
with per-month deltas that are differences of two deterministic runs, saved scenarios (`saved_scenarios`, V7, `/api/scenarios`) with a re-run that reports change, and a builder with a
side-by-side comparison. Acceptance: identity property ✅ (no adjustments / EMI to itself) · each delta explained by named inputs ✅ (`explanations`, `loanEffects`, category effects) ·
saved scenario re-runs identically while data is unchanged ✅ · missing history makes the scenario unavailable with a reason ✅ · KI-011 scenario part ✅. **Not delivered:** investment
contribution adjustments and risk-model re-run (nothing exists to build them on; KI-047), and `POST /api/scenarios/run` as a separate path (the existing simulate endpoint is the ephemeral run).

---

## v0.10.0 — Data-grounded recommendations

**Goal:** prioritised, explainable recommendations derived from the user's own computed metrics.
**Effort:** M. **Depends on:** v0.7.0 (and v0.9.0 for "try this scenario" links).

**Scope**
- Rule engine in `intelligence.recommendations` (no LLM, no external calls). Initial rule set from the original plan: emergency fund below 3 months, EMI above 40 % of income, savings rate below 20 %, one investment class above 70 %, rising spend trend over 3 months; plus data-quality prompts (large uncategorised share, possible duplicate subscriptions from recurring detection). Thresholds are configuration, documented.
- Each item: id, severity (`CRITICAL`/`HIGH`/`MEDIUM`/`INFO`), the **evidence** (metric values and period), explanation, suggested action, optional scenario preset. Sorted by severity then estimated impact. `GET /api/recommendations`.
- Frontend: dashboard panel with expandable cards and an explicit "healthy" empty state; a visible "general guidance, not financial advice" notice.

**Security/correctness:** every recommendation must cite metrics that appear in the analytics/risk responses; no recommendation without sufficient data (data-quality item instead).
**Tests:** one boundary test per rule (just below/at/above threshold), evidence correctness, ordering, no-data behaviour, owner isolation.

**Acceptance criteria**
- [ ] Every recommendation's evidence reproduces from the analytics API for the same period.
- [ ] No recommendation is produced when its inputs are unavailable.
- [ ] Thresholds live in configuration and are listed in the docs.
- [ ] KI-011 closed.

**Risks:** heuristics may not suit all users (label as general guidance; allow dismissing); keep scope to the rules above.
**Done when:** criteria checked, tag `v0.10.0`.

**Delivery status.** Delivered as `intelligence.recommendations` with six rules (savings rate, EMI burden, investment concentration, rising spend, uncategorised share, subscription review), evidence that
reproduces from the analytics API for the same period (tested), strict boundary tests per rule, ordering, owner isolation, configuration properties and a dashboard panel with scenario presets. Acceptance:
evidence reproduces ✅ · no recommendation when inputs are unavailable ✅ (`notEvaluated`) · thresholds in configuration and listed in the docs ✅ · KI-011 recommendation part ✅. **Differences from the plan:**
the *emergency fund below 3 months* rule is never evaluated (FinGuard holds no balances), and there is no dismissal (KI-049). The "try this scenario" link uses a category preset only.

---

## v0.11.0 — Product polish & delivery pipeline

**Goal:** usable on a phone, accessible, observable, and verified end to end in a browser.
**Effort:** L. **Depends on:** v0.10.0 (all features present).

**Scope**
- Responsive layouts and a keyboard/screen-reader pass on every page; loading skeletons; onboarding/empty states; error boundary; pagination and table UX for long lists (KI-030).
- Containerise backend (JRE image) and frontend (static build behind a web server); a single Compose file that starts the whole stack with health checks (KI-032).
- Backend health/readiness endpoint, structured logs with request ids, no sensitive data in logs.
- **End-to-end test suite** (Playwright) driving the full workflow against the Compose stack: register → import → review categories → risk → forecast → scenario → recommendations; run in CI.
- Performance sanity on a generated dataset of realistic size (measure and record; no budget is claimed until measured).

**Acceptance criteria**
- [ ] The e2e workflow passes locally and in CI.
- [ ] Axe/Lighthouse-style accessibility checks show no critical violations on the main pages; layouts verified at phone, tablet, desktop widths.
- [ ] `docker compose up` starts every service healthy from a clean checkout.
- [ ] Recorded measurements for list, analytics and import on the generated dataset.
- [ ] KI-030, 032 closed.

**Risks:** e2e flakiness (mitigate with deterministic seeded data and explicit waits); image sizes.
**Done when:** criteria checked, tag `v0.11.0`.

---

## v1.0.0 — Deployable release

**Goal:** a documented, secured, backed-up deployment with a demo, and a stable API.
**Effort:** M. **Depends on:** v0.11.0. Decision D-11.

**Scope**
- Production deployment configuration for the chosen target (D-11): reverse proxy with TLS, `prod` profile, secrets injection, CORS for the real origin, Compose/production overrides, resource limits.
- Operations: database backup and **restore rehearsal**, migration procedure, runbook (start/stop/upgrade/rollback), log retention.
- Security review: threat checklist, dependency audit for all three stacks, secret scan, confirm no default credentials or dev key are usable in `prod`.
- Demo: a clearly labelled **synthetic** demo dataset and walkthrough (`docs/DEMO.md`).
- Documentation freeze; API treated as stable under semantic versioning from here; retire or regenerate `README.html` (KI-020).

**Acceptance criteria**
- [ ] A clean-machine deployment from the documentation succeeds and passes the e2e suite against it.
- [ ] A restored backup reproduces the data (rehearsed and recorded).
- [ ] The `prod` profile cannot start with development defaults (tested).
- [ ] No open P0/P1 issues; remaining P2/P3 listed.
- [ ] All published claims (accuracy, performance, security) are backed by recorded evidence in the repository.

**Risks:** infrastructure choice and cost (D-11); first real traffic reveals what tests did not.
**Done when:** criteria checked, tag `v1.0.0`.

---

## Traceability: issue → version

| Issue(s) | Version | | Issue(s) | Version |
|---|---|---|---|---|
| KI-018, 022 | v0.1.0 | | KI-008, 025 | v0.5.0 |
| KI-012, 013, 014, 015, 017, 021, 023, 024, 027, 028, 029 (413) | v0.2.0 | | KI-009, 010 | v0.6.0 |
| KI-001, 002, 016, 026 | v0.3.0 | | KI-011 | v0.7.0 – v0.10.0 |
| KI-003, 004, 005, 006, 007, 019, 029 (503) | v0.4.0 | | KI-030, 032 · KI-020 · KI-031 | v0.11.0 · v1.0.0 · owner decision |
