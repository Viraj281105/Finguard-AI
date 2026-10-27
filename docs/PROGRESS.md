# Progress

**As of 2026-10-25.** Evidence-based: everything under "Verified" was observed by running the command shown.
Labels: see [README.md](README.md#status-vocabulary-used-in-every-document).

## 1. Snapshot

| | |
|---|---|
| Latest commit | v0.5.0 to v0.9.0 and the CI fix are pushed to `main` (no tags). The v0.10.0 work is in the commit(s) listed by `git log`. |
| Working tree | **Stage 7 (Version 0.8.0) — Financial Forecasting & Predictive Insights complete.** Statistical, deterministic forecasting in the Java backend (`LEVEL_DAMPED_TREND` 2.0.0; **no machine learning**): cash flow, category spending, budget month-end outlook, savings projection, scenarios, per-user backtest; honest evidence rules (`RELIABLE/LIMITED/UNAVAILABLE`, missing ≠ zero, current month never trains, bands only from ≥ 8 past errors); chronological backtesting without future leakage; forecasting UI; no migration. |
| Version | **0.10.0** aligned across backend, frontend, and AI service |
| Stages 0 – 6 | Done (see §4) |
| Stage 7 — Version 0.8.0 | **Done** (311 backend, 123 frontend, 121 AI = **555 automated tests**; builds, lint and type checks clean) |

## 2. Verified results

**Baseline before any 0.8.0 change** (2026-10-25, `.\scripts\test-all.ps1` on the existing uncommitted tree, which already contained an unreviewed first draft of the forecasting module): 224 backend + 92 frontend + 121 AI = 437, all passing. The draft was audited, found defective (see CHANGELOG 0.8.0 "Changed") and replaced.

| Check | Command | Result (2026-10-25) |
|---|---|---|
| All suites | `.\scripts\test-all.ps1` | "All checks passed", exit 0 |
| Backend tests | `cd backend; .\mvnw -B test` | **311 passed, 0 failed, 0 skipped** (embedded PostgreSQL 15.19; includes the live Java ↔ Python tests; 112 new forecasting tests) |
| Frontend type check / lint | `npm run typecheck`, `npm run lint` | clean |
| Frontend tests | `npm test` | **123 passed** (17 files) |
| Frontend build | `npm run build` | succeeds |
| AI tests / lint / evaluation | `pytest -q`, `ruff check .`, `python -m evaluation.evaluate --check` | **121 passed**, clean, metrics current |
| Synthetic forecast evaluation | `.\mvnw -B -Dtest=SyntheticEvaluationTest test` | deterministic; results in [TESTING.md §7](TESTING.md#7-forecast-evaluation) (method beats baselines on trend/spiky data, loses after a level shift; band coverage 67–80 %) |
| Forecasting UI in a browser | built-in browser pane, mock API with synthetic data, desktop and 375 px | renders; no horizontal overflow at 375 px; two defects found and fixed (net line through the partial month, off-screen table adding scroll height). **Not run against the real backend.** |

**Not verified (never run):** forecast accuracy on **real** user data (none exists), `docker compose up`, the AI Docker image build, `start.ps1`, the backend dev profile against Compose, the frontend against the real backend in a browser, screen-reader behaviour, the new CI steps on a runner, any proprietary bank export (KI-019). See [KNOWN_ISSUES.md](KNOWN_ISSUES.md).

## 3. Capability status

| Capability | Status | Evidence / limitation |
|---|---|---|
| Auth, ledger, import, categorisation, analytics, budgets, goals | Implemented & tested | unchanged since v0.7.0; regression suites green |
| **Cash-flow forecast** (income, spending, net; observed vs projected) | **Implemented & tested** | `ForecastingServiceTest`, `ForecastApiTest`; evidence rules; `UNAVAILABLE` below 3 months; income/net `null` without income |
| **Category spending forecast** with tested trends | **Implemented & tested** | per-category evidence rule; categories without enough history listed with the reason |
| **Budget month-end outlook** | **Implemented & tested** | `BudgetOverrunPredictorTest`; actual equals the budget overview; `INDETERMINATE` is a first-class answer |
| **Savings projection** (surplus ≠ goal contributions ≠ bank balance) | **Implemented & tested** | `verifiedBankBalance` always `null`; shared-surplus goal comparison |
| **Scenario simulator** (hypothetical, non-persistent) | **Implemented & tested** | `ScenarioSimulatorTest`, `ForecastApiTest` (no records change); flat adjustments, no band |
| **Backtesting** (chronological, leak-free) | **Implemented & tested** | `ForecastBacktestTest`; per-user endpoint; synthetic evaluation only (KI-040) |
| Uncertainty bands | Empirical past-error bands only | ≥ 8 past errors; coverage 67–80 % on synthetic data (KI-041); no calibrated probability |
| Seasonality, annual payments | **Not implemented** | KI-042 |
| Forecast accuracy on real data | **Unknown** | KI-040 |
| Risk score, loan/investment scenarios, recommendations | **Not implemented** | v0.7.0 (risk) / v0.9.0 – v0.10.0 |
| Docker stack, `start.ps1`, CI | Implemented, unverified | KI-018 |
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

### Stage 4 (v0.5.0) — AI Transaction Categorisation 
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

### Stage 5 (v0.6.0) — Financial Analytics & Insights
- **Backend `intelligence` module**: Server-side analytics engine (`AnalyticsService`) computing scale-2 `BigDecimal` metrics (cash flow, net expenses, refund offset, savings rate, MoM comparisons, category breakdown); `RecurringExpenseDetector` detecting cadence and commitments; `UnusualTransactionDetector` identifying statistical anomalies; `InsightGenerator` producing rule-based observations. 14 typed DTOs.
- **Exclusion of non-spending flows**: `TRANSFERS`, `CARD_PAYMENTS`, and `INVESTMENTS` strictly excluded from spending totals; `REFUNDS` offset gross expenses.
- **Frontend analytics & charts**: Responsive SVG `CashFlowChart`, SVG donut `CategoryBreakdownChart`, KPI cards, `InsightsFeed`, `RecurringExpensesCard`, `UnusualSpendingCard`, `DataQualityCard`, `DateRangeSelector`. Client-side arithmetic completely removed.
- **Verification**: 178 backend tests (+16), 85 frontend tests (+5), 121 AI tests (384 automated tests). Resolved KI-009, KI-010.

### Stage 6 (v0.7.0) — Budgeting & Financial Goals
- **Backend `budgeting` module**: Dedicated domain (`com.finguard.backend.budgeting`) with `BudgetService` and `GoalService`.
- **Monthly budgeting engine**: Overall and category spending limits per month (`yearMonth = YYYY-MM`); actual spending dynamically computed from ledger debits minus refunds; duplicate budget prevention (409 Conflict) and database partial unique indexes (`uq_budgets_user_month_category`, `uq_budgets_user_month_overall`); performance tracking (`UNDER_BUDGET`, `APPROACHING_LIMIT`, `OVER_BUDGET`), unbudgeted spending aggregation, and limit warnings.
- **Financial goals system**: Goal targets with target dates, required monthly contribution pacing, overdue flags, and automatic `ACTIVE` ↔ `COMPLETED` transitions.
- **Concurrency-safe contributions**: PostgreSQL pessimistic row locking (`@Lock(LockModeType.PESSIMISTIC_WRITE)`) prevents race conditions during contribution recording and deletions.
- **Banking disclaimer**: Explicitly documents and communicates that goal contributions are self-tracked manual allocations for planning purposes, not verified bank balances or automated bank transfers.
- **Flyway V6 migration**: `V6__budgeting_and_financial_goals.sql` creating `budgets`, `financial_goals`, `goal_contributions` with check constraints and partial unique indexes.
- **Frontend experience**: `BudgetsGoalsPage` with dual tabs ("Monthly Budgets" and "Financial Goals"), `MonthSelector`, `BudgetSummaryCard`, `CategoryBudgetCard`, `AddBudgetModal`, `GoalCard` with pacing and progress rings, `AddGoalModal`, `AddContributionModal`, `GoalContributionsModal`, and `BudgetHealthPreviewCard` on dashboard.
- **Verification**: 197 backend tests (+19), 88 frontend tests (+3), 121 AI tests (**406 automated tests**); ArchUnit 11 rules, typecheck, lint, build clean. Versions aligned to `0.7.0`.

### Stage 7 (v0.8.0) — Financial Forecasting & Predictive Insights (this change)
- **Audit first:** the tree already held an uncommitted draft of the forecasting module and UI. It was read, run (437 tests green) and found to violate the requirements (in-progress month used as history, gaps compressed, always-claimed "80 %" band, recurring double-counted in budget run-rates, naive extrapolation labelled HIGH confidence, scenarios on empty baselines, goals each claiming the whole surplus). Core replaced; nothing from earlier stages was rewritten or its migrations touched.
- **Backend:** `ForecastMethod` / `ForecastBacktest` (pure), `HistoryLoader` (one bounded owner-scoped query, analytics definitions), `ForecastingService`, `BudgetOverrunPredictor`, `ScenarioSimulator`, typed DTOs, `ClockConfig`; `AnalyticsService` gained two shared pure helpers; `GlobalExceptionHandler` maps parameter type mismatches to 400. No migration, no cache, no new dependency.
- **Frontend:** `/forecasting` page (cash flow, categories, budget outlook, savings outlook, scenarios, evaluation), dashboard preview; independent section loading and errors; accessible chart.
- **Verification:** 311 backend (+87), 123 frontend (+31), 121 AI (**555 tests**, +118 over the 437 baseline); full script, build, lint, type check clean; UI checked in the browser pane against a synthetic mock API.
- **Deliberately not done:** ML or LLM forecasting, seasonality, a Python forecast endpoint, forecast history persistence, calibrated probabilities.

### Stage 8 (v0.9.0) — Scenario simulator
- **Backend:** `LoanAmortization`; loan close / prepay / change-EMI and one-time items in the simulate endpoint with `loanEffects`; migration **V7** `saved_scenarios`; `/api/scenarios` (save, list, get, re-run, delete) with owner isolation, a 50-per-user cap and re-run reproducibility reporting.
- **Frontend:** loan and one-time builder, loan-effects table, save, saved list, re-run and a side-by-side comparison (up to three, same horizon).
- **Verification:** 332 backend, 137 frontend, 121 AI = **590** tests; builds, lint and type check clean. UI verified in jsdom only, not in a browser against the real backend.
- **Deliberately not done:** investment-contribution scenarios and risk re-run (nothing to build them on; KI-047), editing saved scenarios (KI-048). Loan balances are estimates (KI-046).
- Not committed, not tagged.

### Stage 9 (v0.10.0) — Data-grounded recommendations
- **CI first:** the Frontend job had failed since at least 2026-10-22 (Node 20 vs vitest 5 / jsdom 30 needing Node >= 22 / 24.15); switched to Node 24 and confirmed all three jobs green on GitHub.
- **Backend:** `intelligence.recommendations` (`RecommendationEngine` pure rules, `RecommendationService`, configuration properties, controller); `GET /api/recommendations`; no migration.
- **Frontend:** dashboard Recommendations panel and 'Try this scenario' presets into the simulator.
- **Verification:** 354 backend, 146 frontend, 121 AI = **621** tests; builds, lint and type check clean. UI verified in jsdom only.
- **Deliberately not done:** emergency-fund rule (no balances), dismissal (KI-049); rules tested with synthetic data only (KI-050).

## 5. Outstanding issues (highest priority first)

Full list with evidence and target versions: [KNOWN_ISSUES.md](KNOWN_ISSUES.md).

1. **KI-040** — forecast quality on real histories is unknown; synthetic evidence shows the method loses to simple baselines after a level shift.
2. **KI-041/042/043** — bands are weakly calibrated (67–80 % on synthetic data) and rare for short histories; no seasonality or annual payments; scenarios are flat and single-point.
3. **KI-030** — the classifier is rules only; real-world quality is unknown. **KI-037** — goal contributions are self-tracked, not verified balances.
4. **KI-018** — Docker stack unverified. **KI-019** — proprietary bank exports unverified. **KI-045** — forecasting UI not checked against the real backend.
5. **KI-031–036** — confidence is a rule tier; optional shared secret; synchronous classification; live contract tests need Python; `PUT` category semantics.

## 6. Blockers and open questions

| Item | Needed from | Blocks |
|---|---|---|
| Authorisation to **commit** v0.6.0, v0.7.0 and v0.8.0 and to tag any release (v0.5.0 was committed and pushed on request; the rest has not been) | Owner | everything downstream |
| Explicit approval to begin Stage 8 (v0.9.0) | Owner | Stage 8 start |
| Real, anonymised bank statements and labelled data (D-06) | Owner | any statement about real classifier or forecast accuracy |
| Docker Desktop running | Developer machine | KI-018 |

## 7. Next steps

**Stage 9 (v0.10.0) is complete.** In accordance with execution rules:
- **STOP and wait for the owner's explicit approval before starting Stage 10 (v0.11.0, polish and delivery).**
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
| 2026-10-24 | Stage 5 (Version 0.6.0) Financial Analytics & Insights completed. Server-side `intelligence` module implementing deterministic cash flow, net calculations, refund offsets, MoM comparisons, category spending breakdowns, recurring payment commitments detection, explainable statistical outlier detection, data coverage diagnostics, and rule-based insights. Complete removal of client-side money arithmetic (`computeSummary` in browser eliminated). Polished, cohesive light interface with SVG dual-bar cash flow chart, SVG donut category breakdown chart, KPI cards, filterable insights feed, recurring obligations card, attention-needed outliers, data diagnostics card, date range selector, and zero-transaction empty state. 178 backend tests, 85 frontend tests, 121 AI tests (384 automated tests). Resolved KI-009, KI-010. Not committed. |
| 2026-10-24 | Stage 6 (Version 0.7.0) Budgeting & Financial Goals completed. Server-side `budgeting` module (`BudgetService`, `GoalService`) implementing monthly spending budgets (overall and per category), actual-versus-budget spending comparisons, duplicate prevention (409 Conflict), unbudgeted spending aggregation, contextual warnings (approaching limit $\ge 80\%$, over budget $> 100\%$), and savings goals tracking with target date pacing, automatic completion, and concurrency-safe contribution recording using pessimistic row locking. Clear banking disclaimer returned in API and displayed in UI. Polished React interface with dual tabs ("Monthly Budgets" and "Financial Goals"), month navigation selector, overall budget summary card, category progress bars, goal cards with remaining days and monthly pacing, and dashboard integration card. Flyway V6 migration (`V6__budgeting_and_financial_goals.sql`). 197 backend tests, 88 frontend tests, 121 AI tests (**406 automated tests**). Aligned versions to `0.7.0`. Not committed. |
| 2026-10-25 | Stage 7 (Version 0.8.0) Financial Forecasting & Predictive Insights completed. An uncommitted first draft of the forecasting module was audited and replaced: `LEVEL_DAMPED_TREND` 2.0.0 (robust level + significance-tested damped trend, Java, no ML), evidence rules (complete months only, missing ≠ zero, ≥ 3 months, income/net null without income), empirical past-error bands (≥ 8 samples), chronological leak-free backtesting with naive and mean-of-3 baselines, budget month-end outlook without double-counted recurring payments, savings projection separating surplus / goal contributions / bank balance, validated non-persistent scenarios, forecasting UI. Synthetic evaluation reported with its losses (level shifts); no real-data evaluation exists. 311 backend (+87), 123 frontend (+31), 121 AI = 555 tests (baseline 437), all passing. Not committed. |
