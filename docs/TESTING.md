# Testing

Results below are from a real run on **2026-10-28** (`.\scripts\test-all.ps1`, exit code 0, 665 automated tests: 387 backend, 150 frontend, 128 AI). Re-run before editing
the numbers.

## 1. Strategy

| Layer | Purpose | Tooling |
|---|---|---|
| Unit | Pure logic: parsers, JWT, AI client, classifier rules, formatting, summary maths | JUnit 5 + AssertJ + Mockito / MockRestServiceServer; Vitest |
| Integration (backend) | The real application, real SQL, real security chain, via HTTP (MockMvc) | `@SpringBootTest` on **embedded PostgreSQL 15** (zonky) |
| Component (frontend) | Pages and forms with the API module mocked | Vitest + Testing Library + jsdom |
| Architecture | Module boundaries cannot erode | ArchUnit |
| Migration | Fresh install and legacy-database adoption | `LegacyDatabaseAdoptionTest`, `BackendApplicationTests` |
| Contract (Java ↔ Python) | The backend client against the **real** AI service process, and against a recorded exchange both sides verify | `LiveAiServiceContractTest`, `LiveAiImportTest`, `contracts/classify-examples.json`, `contracts/categories.json` |
| End to end (browser → backend → DB) | **Does not exist yet** | planned v0.11.0 |

Rules: a bug fix starts with a failing test; ownership/security rules need a test shown to fail when the rule is
removed (done once for `TransactionService`, see §5); test fixtures for bank statements are synthetic and say so
(`backend/src/test/resources/fixtures/statements/README.md`).

## 2. Commands

| Suite | Command | Needs |
|---|---|---|
| All | `.\scripts\test-all.ps1` | JDK 21, Node, the AI venv; network on first run |
| Backend | `cd backend; .\mvnw -B test` | JDK 21 (no Docker, no local PostgreSQL) |
| Backend Coverage | `cd backend; .\mvnw test jacoco:report` | HTML report in `backend/target/site/jacoco/index.html` |
| Live AI contract tests | `.\mvnw -B -Dtest=LiveAiServiceContractTest,LiveAiImportTest test` | `ai/.venv` or `FINGUARD_AI_PYTHON`; `FINGUARD_REQUIRE_LIVE_AI=true` makes a missing interpreter a failure instead of a skip |
| One backend class | `.\mvnw -B -Dtest=OwnershipApiTest test` | |
| Frontend | `cd frontend; npm run typecheck; npm run lint; npm test` | `npm install` |
| Frontend Coverage | `cd frontend; npm run test:coverage` | Report in `frontend/coverage/` |
| AI service | `cd ai; .\.venv\Scripts\python -m pytest -q; .\.venv\Scripts\python -m ruff check .` | venv with `-e ".[dev]"` |
| Classifier evaluation | `cd ai; .\.venv\Scripts\python -m evaluation.evaluate` (`--check` to verify `metrics.json`) | synthetic data; see `ai/evaluation/report.md` |
| Production build | `cd frontend; npm run build` | |

## 3. Inventory (observed)

### Backend — 387 tests, 0 failures, 0 skipped

| Class | Tests | What it proves |
|---|---:|---|
| `BackendApplicationTests` | 3 | Whole app boots on real PostgreSQL; Flyway V1–V6 applied; the nine tables exist; Hibernate `validate` passes |
| `migration.LegacyDatabaseAdoptionTest` | 3 | A pre-Flyway database with legacy rows is baselined and upgraded through V2–V6; lowercase email normalization; transaction dates backfilled; loan interest rate widened; V4 `upload_jobs` columns; V5 category normalisation and constraints; V6 budgets, financial goals, and goal contributions constraints |
| `ProfilesTest` | 4 | `dev` boots with defaults; `prod` refuses to start without `JWT_SECRET`; `prod` starts with one; `SecurityEnvironmentGuard` halts startup if dev key is used with non-local DB |
| `identity.AuthApiTest` | 18 | Register/login/refresh/logout contracts; 409 duplicate; field-level 400; min 10-char password (D-02); case-insensitive email normalization (D-05); refresh token rotation; token reuse detection revoking family (D-03b); 5 failed logins throttle 6th with 429 and `Retry-After: 60` (D-04); cookie-based refresh |
| `identity.JwtServiceTest` | 8 | Round trip; `typ` separation in both directions; expiry; foreign signature; garbage; unique tokens; weak/missing secret fails fast |
| `common.HttpErrorContractTest` | 7 | Framework errors return uniform `ApiError`: unknown route → 404, unsupported method → 405, unsupported media type → 415, unaccepted media type → 406; standard security headers present (`nosniff`, `DENY` frames, `strict-origin-when-cross-origin`, CSP); **a query parameter of the wrong type (`horizonMonths=abc`) is 400, not 500** |
| `ledger.OwnershipApiTest` | 4 | For transactions, investments and loans: another user gets 404 on read/update/delete and sees nothing in lists; the owner is unaffected; unknown ids are 404 |
| `ledger.LedgerApiTest` | 13 | Decimal precision and response shape; transaction dates persisted; creation without date defaults to today; date update; pagination headers; query filtering; deterministic sorting; query validation 400; loan defaults; optional investment value |
| `ledger.TransactionFingerprintTest` | 7 | Deterministic SHA-256 calculation; user isolation; date sensitivity; direction sensitivity; amount scale normalization; title whitespace normalization; intra-file occurrence index differentiation |
| `ingestion.parser.StatementParserRegistryTest` | 15 | Dynamic header-based detection and parsing for all 5 banks on synthetic fixtures, reordered headers, ICICI layout 2, extra columns, diverse amount/date formats, UTF-8 BOM, reconciliation invariant, PII-sanitized row error snippet masking |
| `ingestion.ImportApiTest` | 13 | Upload → 202 → async completion → transactions persisted with real statement dates; reconciliation invariant validation; user job history; Windows-1252 fallback decoding; binary null-byte rejection; repeated uploads skip duplicates; identical statements isolate; legitimate same-day identical transactions both import; error handling; AI-down fallback |
| `ingestion.ImportClassificationTest` | 10 | Predictions and full provenance persisted; transfers, refunds, card payments and investments keep their amounts and directions; throwing AI client never fails import; user category never overwritten; re-import keeps user categories |
| `ingestion.service.ImportRecoveryTest` | 1 | Interrupted jobs in `PENDING` or `PROCESSING` state are detected on application startup and transitioned to `FAILED` with safe re-upload instructions |
| `ingestion.web.UploadErrorApiTest` | 3 | Upload exceeding 5 MB limit returns 413 `ApiError`; empty upload returns 400 `ApiError`; executor queue saturation returns 503 `ApiError` with retry guidance |
| `ai.AiClientTest` | 17 | Request body; full result mapping; long descriptions truncated; batching; fail-fast budget; invalid predictions dropped; stops after two consecutive failed batches; time budget |
| `ai.AiClientHttpTest` | 5 | Real HTTP: shared-secret header sent only when configured; read timeout cutoff; closed port and HTML error page degrade to unavailable |
| `ai.LiveAiServiceContractTest` | 4 | Real FastAPI process: `/health` capabilities; recorded results from `contracts/classify-examples.json`; missing/wrong shared secret refused; 422 degradation |
| `ingestion.LiveAiImportTest` | 1 | Nothing mocked: upload → import → HTTP → real classifier → database; provenance persisted |
| `ledger.CategoryApiTest` | 13 | `PATCH …/category` records `USER`, clears automatic provenance, accepts any letter case and legacy labels; cross-user 404; auth required; `UNCATEGORIZED` and `needsReview` filters; `reclassify` |
| `ledger.TaxonomyContractTest` | 5 | Java enum, flows and directions equal `contracts/categories.json`; the V5 `CHECK` lists exactly the same ids; non-spending flows; parsing |
| `ArchitectureTest` | 12 | The dependency rules in [ARCHITECTURE.md](ARCHITECTURE.md), enforcing isolation across common, identity, ledger, ingestion, categorization, ai, intelligence, and budgeting |
| `intelligence.AnalyticsServiceTest` | 7 | Deterministic hand-calculated golden datasets: net expenses, refund offset, net cash flow, savings rate handling (with zero income), MoM comparisons, category breakdown, month-series grouping, user isolation |
| `intelligence.RecurringExpenseDetectorTest` | 4 | Recurring payment detection: monthly ~30-day cadence, weekly ~7-day cadence converted to monthly, amount variance tolerance (≤ 15%), rejecting 2-month/irregular frequencies |
| `intelligence.UnusualTransactionDetectorTest` | 3 | Explainable statistical outlier detection: 2.5x median category benchmark, mean + 2 sigma, thresholding, skipping insufficient histories |
| `audit.E2eAuditRegressionApiTest` | 14 | **Defects found by the end-to-end audit** ([AUDIT_E2E_2026-10-27.md](AUDIT_E2E_2026-10-27.md)): CORS exposes the pagination headers (and still rejects other origins); `31/02/26` rejected; dates outside 1990-today rejected; a negative credit refused while a parenthesised debit stays an expense; a 10,000-character narration and a 40-digit amount do not fail the statement; exactly 10,000 rows accepted, 10,001 refused; 6 parallel overlapping imports all `COMPLETED` and 40 rows stored once; oversized/over-precise values and a 500 % interest rate are 400; no multipart body is 400; 8 concurrent refreshes of one token succeed exactly once; budget overview honours `yearMonth` and `month`; recurring share is per month (51.95 % by hand) |
| `audit.E2eAuditRegressionUnitTest` | 2 | A database outage maps to 503 without leaking details; a ledger with no month in the last 12 gives `UNAVAILABLE` (cash flow and categories), not an exception |
| `audit.ReleaseReadinessRemediationTest` | 11 | Release-readiness findings R-1 to R-7 (nested groups: application defect fixes 2, financial correctness 3, authentication and security 6) |
| `intelligence.RecommendationEngineTest` | 15 | v0.10.0 rules on SYNTHETIC inputs: a healthy period gives nothing (and only the emergency-fund check cannot be evaluated); **boundaries per rule** - savings rate at 20 / 19.99 / 10 / 9.99 / 0 / -0.01, EMI share at 40 / 41 / 60 / 61 %, class share at 70 / 71 %, rise at 6.67 / exactly 10 % / not strictly rising, uncategorised at 20 / 20.01 % and < 10 transactions, subscriptions at 2 / 3 / duplicate merchant; evidence values, sources and impacts by hand (2,500; 500; 3,000); missing income, too few months, non-consecutive months reported as not evaluated; ordering by severity then impact then id; preset only with a discretionary category; thresholds configurable; deterministic |
| `intelligence.RecommendationApiTest` | 7 | GET /api/recommendations over HTTP (clock fixed, synthetic ledgers): 401; no history = no items and reasons; **evidence equals `/api/analytics/summary` for the same period** (8 %, income, spending, EMI 25,000); ordering HIGH items by impact (6,000 before 5,000); preset body; a healthy ledger is `healthy`; **owner isolation**; one month of data invents nothing; the current month never counts |
| `intelligence.AnalyticsApiTest` | 2 | Contract & security: `/api/analytics/*` requires 401 unauthorized, validates date ranges (`from > to` returns 400), enforces user isolation, returns complete payload contracts |
| `budgeting.BudgetServiceTest` | 7 | Monthly budgeting logic: overall and category spending evaluation, refund netting, non-spending exclusions, duplicate prevention (409 Conflict), unbudgeted spending, threshold states (`UNDER_BUDGET`, `APPROACHING_LIMIT`, `OVER_BUDGET`), contextual warnings |
| `budgeting.GoalServiceTest` | 7 | Financial goals tracking: target date monthly pacing, overdue detection, automatic `ACTIVE` to `COMPLETED` transitions, contribution deletion with status rollback, user isolation, banking disclaimers |
| `budgeting.BudgetApiTest` | 3 | Budget REST integration: CRUD endpoints, month overview, duplicate 409 Conflict, user isolation |
| `budgeting.GoalApiTest` | 2 | Goal REST integration: CRUD endpoints, contributions with banking disclaimer, user isolation |
| `forecasting.ForecastMethodTest` | 15 | The method against hand-worked examples: level = mean of the last observations; a perfect trend extrapolated with damping (158 / 164.4 / 169.52); noise that does not support a trend gives the level; no trend with < 6 months; one huge month clipped (106); **a missing month is a gap on the calendar axis, not a zero**; floor at zero; zero history projects zero only because zero was observed; **only observations at or before the origin are read**; > 12-month-old data ignored; calendar index round-trips over year and leap-day boundaries; determinism |
| `forecasting.ForecastBacktestTest` | 16 | **No future leakage**: replacing every value from a target month onward (and for multi-step horizons everything after the origin) leaves the forecast for that month identical; scoring needs ≥ 3 earlier months; an unobserved target is not scored as zero; MAE/RMSE/bias/WAPE against a hand calculation; **WAPE is undefined (`null`), not infinite, when every actual is zero**; verdicts need ≥ 6 samples and a 10 % margin; the method beats both baselines on a clean linear trend; empirical band = point + nearest-rank 10th/90th percentile of past errors, none below 8 samples, clamped at zero, always contains the point; net runs align on target months |
| `forecasting.service.ForecastingServiceTest` | 31 | **Hand-computed golden ledgers** (synthetic): 6 stable months ⇒ 50,000 / 30,000 / 20,000 a month, totals 150,000 / 90,000 / 60,000, 40 % rate; current month shown but not used; empty ledger and 2 months ⇒ `UNAVAILABLE` with no projection and `null` summary; exactly 3 months ⇒ `LIMITED`; a gap is unknown not zero; a stale import; transfers-only months excluded; partial first month; **no income ⇒ income/net `null`, never 0**; income in 2 of 6 months not enough; irregular income; income-only month = real zero spending; **transfers, card payments, investments excluded; refunds offset; uncategorised credit = income — and history equals `AnalyticsService` figures exactly**; outlier month limited and reported; damped trend by hand (32,880 / 33,904); **recurring payment inside the history is not added twice**; recurring floor lifts only a lower projection; no band with short history, band from past errors with long history; leap day and year boundary; determinism; categories: too-little-spending categories listed with the reason, tested rising trend (8 %/month) flagged, intermittent category `LIMITED`, same-category credits net |
| `forecasting.service.BudgetOverrunPredictorTest` | 17 | **Facts vs forecasts**: spending above budget ⇒ `EXCEEDED`; too little evidence ⇒ `INDETERMINATE` with `null` numbers and a reason; two estimates by hand (run-rate 18,600, history 30,000, central 24,300) ⇒ `ON_TRACK` / `AT_RISK` / `LIKELY_OVER` at 40,000 / 28,000 / 17,000; **a single naive extrapolation never gives `LIKELY_OVER`**; **recurring already paid is excluded from the daily rate (37,050, not 79,050)**; recurring not yet paid is added once and named; committed recurring alone can make a budget `LIKELY_OVER`; category scope; finished and future months; stale data ⇒ warning and recorded days only; out-of-range month rejected; actual spend follows the budget definition (refund offset, non-spending excluded); no budgets ⇒ warning; determinism |
| `forecasting.service.ScenarioSimulatorTest` | 14 | No adjustments ⇒ scenario = baseline; by hand: groceries −20 % ⇒ net +5,700 over 3 months, savings rate 40 → 43.8; housing −₹2,000 with income +10 % ⇒ +21,000; fixed income drop; income cannot go below zero (savings rate `null`, not 0 %); added recurring expense (with and without a category); **removing a detected recurring payment lowers its category once, and combined with a 100 % cut cannot go below zero**; adjustment to a category without a forecast ignored with a warning; **no baseline ⇒ `available = false` and no numbers**; income scenario needs an income baseline; ten validation failures; purity |
| `forecasting.service.LoanScenarioTest` | 13 | v0.9.0 loan arithmetic against published reference values (1,00,000 at 12 % for 12 months = 8,884.88; 10,00,000 at 10 % for 120 months = 13,215.07; zero rate), total interest 6,618.55 +/- 0.20, balance after the term ~ 0, an EMI below the interest is rejected; **identity**: no adjustment equals the baseline and an EMI changed to itself changes nothing; closing a loan = lump sum now and exactly the EMI removed afterwards, interest saved = remaining interest; a prepayment shortens the loan and saves interest, more saves more (monotonic); a lower EMI lowers outflow but costs interest; one-time items land only in their month; unknown loan, missing amount, duplicate loan, month beyond the horizon rejected; deterministic |
| `forecasting.SavedScenarioApiTest` | 7 | Saved scenarios over HTTP (clock fixed, synthetic ledgers): 401 on every endpoint; save / list / read back with method version and snapshot; **re-run is unchanged while data is unchanged, reports change when a past month gains a transaction, and never overwrites the snapshot**; blank name, invalid adjustment and unavailable baseline are 400 and save nothing; **owner isolation** (list empty, get / re-run / delete 404 for another user); delete; saving does not change the ledger |
| `forecasting.SyntheticEvaluationTest` | 4 | Deterministic synthetic evaluation (see §7); writes `backend/target/forecast-evaluation/synthetic-evaluation.md`; averaging beats "last month repeats" on stable noise; the method beats both baselines on a growth trend; every pattern yields enough scored forecasts |
| `forecasting.ForecastApiTest` | 15 | HTTP on real PostgreSQL with the clock fixed at 2026-07-15 (synthetic ledgers): **all six endpoints are 401 unauthenticated**; horizon and month validation (0, 13, `abc`, malformed and out-of-range months ⇒ 400); cash flow built from persisted data, observed vs projected separated; **forecast history equals `/api/analytics/monthly`**; no history ⇒ `UNAVAILABLE` everywhere (no zeros); **user isolation** (two users with different ledgers; a newcomer sees none); categories with and without a forecast; **budget outlook actual = budget overview actual**, `EXCEEDED` fact, `INDETERMINATE` with no invented number; another user's budgets never appear; savings keep projected surplus, recorded goal contributions and bank balance apart (`verifiedBankBalance` null; goal pace 10,800; 3 months; 2026-10); **a scenario changes no transaction, budget or goal** (before/after JSON compared); scenario validation messages; scenario without baseline; backtest sample sizes and `INCONCLUSIVE` |

### Frontend — 19 files, 150 tests, 0 failures (plus `tsc -b`, `eslint`, `vite build` clean)

`shared/lib/format`, `shared/auth/tokenStorage`, `shared/api/client` (bearer header; 401 handling; silent refresh with queued request retry; logout on refresh failure; error-message selection), `shared/hooks/useResource`, `app/PrivateRoute`, `features/dashboard/summary`, `features/auth/AuthPages` (login, registration regression, duplicate and validation messages, in-flight state), `features/statement-import/CsvUpload` (file-type and size rejection, upload → poll → completion → refresh, duplicate reporting, reconciliation metrics breakdown, expandable error inspector), `features/transactions/TransactionsPage` (loading, real date display, pagination controls, filtering, empty, error, delete, add via modal), `TransactionsPage.categories` (15: automatic vs user-confirmed vs missing categories; review flag and queue; inline correction; one-click confirm; "Needs a category" filter; categorize automatically; totals exclude non-spending), `features/statement-import/UploadProgress` (5: categorisation notice), `shared/categories` (4: equals `contracts/categories.json`), `features/dashboard/DashboardPage.test.tsx` (5: loading skeleton, populated dashboard rendering, KPI cards, MoM comparison pills, interactive visualizations, recurring commitments, attention needed, data diagnostics, empty state, date range preset filters), `features/budgeting/BudgetsGoalsPage.test.tsx` (3: loading skeleton, empty state with creation guidance, populated monthly budgets and active financial goals rendering with explicit banking disclaimer).

**Recommendations panel (v0.10.0, `RecommendationsPanel.test` 7 tests + 2 preset tests in `ScenarioLoansAndSaved.test`, SYNTHETIC fixtures):** the notice, period and items in the order given with severity and impact; expanding shows explanation, action and evidence with its reproducible source; Try this scenario only where there is a preset and the preset reaches the forecasting page; the simulator starts with the suggested category cut (and ignores a category without a forecast); not-checked reasons; the 'nothing stands out' state; a thin history is never called healthy; error with Retry.

**Scenario builder and saved scenarios (v0.9.0, `ScenarioLoansAndSaved.test`, 14 tests, SYNTHETIC fixtures):** a loan prepayment is sent only when complete and the loan-effect table shows payoff months and interest saved; closing needs no amount; no usable loan explains how to enable it; one-time items are sent only when complete and within the horizon, income disabled without an income baseline; saving requires a name, calls the API with the current request, refreshes the list and shows server errors; the saved list has empty and error states; up to three same-horizon scenarios are compared side by side (different horizons are disabled); re-run shows 'same result' or the saved and current figures, and flags a changed method version; delete reloads the list.

**Forecasting UI (v0.8.0, 3 files, 35 tests, all against SYNTHETIC API fixtures):** `ForecastingPage.test` (loading → evidence note; actual / month-so-far / projected months distinguished in chart labels and legend; **band only where the backend supplied one**; server-computed totals; method and assumptions; recurring payments not added twice; horizon change refetches cash flow, categories and savings; **too-little-history refuses to project, shows facts only, no projected numbers**; limited reliability with the warning that caused it; **"Not projected" instead of zero without income**; a failing section shows an error with Retry without blanking the page; category table with tested trend, evidence, unusual months and the categories left out; budget outlook labels facts, ranges and missing evidence differently and explains each assessment; savings keep projected surplus / recorded goal contributions / bank balance apart and compare goals without double-counting; evaluation shows sample sizes and "Inconclusive"; dashboard preview shows next-month projection or nothing), `ScenarioSimulatorCard.test` (starts from the baseline and says hypothetical; debounced backend recalculation after an income change with baseline / scenario / change; request contains only well-formed changes; reset; server validation message shown; **a slow outdated response never overwrites a newer one**; no controls without a baseline; income disabled without observed income), `format.test`.

### AI service — 128 tests, 0 failures (plus `ruff`)

| File | Tests | What it proves |
|---|---:|---|
| `test_classifier.py` | 87 | **Every one of the 18 assignable categories** has ≥ 2 positive cases (50 narrations incl. credits/debits, UPI/NEFT/IMPS/POS styles); a test asserts every category is covered; 12 **unknown / ambiguous** narrations abstain with a reason (UPI to a person, unknown shop, ATM, gateways, empty); equal-weight rules for two categories are an ambiguity with alternatives; specific beats generic (instamart, amazon prime, amazon pay shopping, big bazaar groceries, school fees, card annual fee, education loan EMI); token-boundary matching (`rent` in `parental`); a credit from an expense merchant and a debit that says `salary`/`refund` are not asserted; card payments, refunds, transfers, investments in both directions; determinism; unique rule ids; the `ClassifierChain` lets a stub model complement the rules |
| `test_classify_api.py` | 25 | Batch in/out and order; legacy request shape; empty batch; unknown fields ignored; determinism; **13 malformed-request cases → 422**; batch cap; non-JSON; method not allowed; **descriptions never appear in logs**; shared-secret 401/200 and open `/health`; **the recorded contract file equals the live output** |
| `test_evaluation.py` | 3 | `metrics.json` equals the classifier; datasets well-formed and labelled synthetic; metric arithmetic on a hand-computed example |
| `test_health.py` | 5 | `capabilities` equals the routes that exist (via OpenAPI); no ML or calibration claim; unimplemented endpoints are 404 |
| `test_normalize.py` / `test_taxonomy.py` | 6 / 2 | Normalisation rules; taxonomy equals `contracts/categories.json` |

**Classifier evaluation (not a test):** `ai/evaluation/report.md` — measured on **synthetic data written by the rules' own author**. First-look hold-out result 92.5 % accuracy (133 rows, 1 wrong category asserted, 9 declined). It is not an estimate of real-world accuracy and no ML model exists to evaluate. Latency measured in-process for 200 items: ≈ 25 ms (machine-dependent; regenerated by the report command).

## 4. Coverage gaps (what is **not** tested)

| Gap | Why it matters | Planned |
|---|---|---|
| Real bank statements; tested on synthetic fixtures and permutations | Proprietary exports from actual net banking portals may contain undocumented quirks (KI-019) | v0.4.0–v1.0.0 ongoing |
| Loans/Investments pages, dashboard page, `CsvUpload` drag-and-drop, `AddLoan/AddInvestment` modals | Component tests cover auth/transactions/upload/summary | v0.3.0–v0.11.0 as touched |
| Browser end-to-end flow, responsive layout, accessibility | No Playwright/Cypress; jsdom only | v0.11.0 |
| Docker Compose stack, `start.ps1`, the AI image build | Docker daemon unavailable (KI-018) | gate for v0.1.0 |
| Performance and large datasets | No measurements exist | v1.0.0 |
| ~~Contract test between the Java client and the real Python service~~ | **Done in 0.5.0** (`LiveAiServiceContractTest`, `LiveAiImportTest`, recorded contract). The CI wiring for it has not been run on a CI runner. | — |
| Classifier accuracy on **real** statements and calibrated confidence | Evaluation data is synthetic and optimistic (KI-030/031) | When D-06 data exists |
| The new category UI in a real browser (layout, keyboard use of the inline picker, narrow screens) | jsdom only | v0.11.0 |
| Concurrent classification vs. concurrent user edits under real load | Covered by one deterministic race test (a correction made *during* classification), not by a stress test | — |

## 5. Known limitations of the existing tests

- **Synthetic fixtures** prove the documented layouts parse, not that real exports do.
- **Embedded PostgreSQL 15.19** matches the Docker image's major version, not necessarily its patch level or configuration.
- **Ownership mutation check:** `TransactionService.getById` was temporarily changed to an unscoped `findById`;
  `OwnershipApiTest.transactionsAreIsolatedBetweenUsers` failed (404 expected, 200 returned); the change was reverted. Done once,
  by hand, for transactions only; investments and loans rely on the same test shape but were not mutation-checked.
- Frontend tests mock the API modules; they do not prove the real backend and frontend agree on field names. (The
  response DTO shapes are covered on the backend side by `LedgerApiTest`; there is no shared contract test yet.)
- Test output contains expected `WARN`/`APPLICATION FAILED TO START` lines (the `prod` fail-fast test is intentional) and a Mockito
  self-attach warning (KI-024).
- Timing-based tests use Awaitility (≤ 20 s) and fake timers; they have not been run repeatedly to measure flakiness.

## 6. Adding tests — checklist

1. Backend HTTP behaviour → extend `support.IntegrationTest`; register users with `registerUser()` (unique emails, no cleanup needed).
2. Anything that reads user data → add a cross-user case to `OwnershipApiTest`.
3. New migration → add a data-bearing case to `migration.LegacyDatabaseAdoptionTest`.
4. New module dependency → the `ArchitectureTest` rules must stay green; extend them for new modules.
5. Frontend → mock `./api` of the feature, never axios; assert on roles/labels, not class names.
6. Financial calculations (from v0.6.0) → golden datasets with expected values computed by hand and written in the test.

## 7. Forecast evaluation

**What exists and what does not.** Forecast quality is measured in two ways, and only one of them has been run:

1. **Synthetic evaluation (run; reproducible).** `SyntheticEvaluationTest` generates 40 series per pattern from fixed seeds, scores the method against two
   baselines by chronological rolling origin and checks band coverage. Command: `cd backend; .\mvnw -B -Dtest=SyntheticEvaluationTest test`;
   output: `backend/target/forecast-evaluation/synthetic-evaluation.md` (deterministic; the test runs it twice and compares).
2. **Evaluation on a user's own history (implemented; not run on any real user's data).** `GET /api/forecast/backtest` computes the same
   rolling-origin scoring on the caller's persisted months at request time. **No real user data was available to this project, so no
   result on real data exists, and none is claimed.** With few months the endpoint reports sample sizes and `INCONCLUSIVE`.

**Method.** For each observed target month and horizon `h`, the forecast origin is `target − h`; only months at or before the origin are
passed to *any* forecaster, so future data cannot leak (proved by `ForecastBacktestTest` by corrupting everything after the origin). Targets need ≥ 3
earlier months. Baselines: *naive* (last month repeats) and *mean of the last 3 months*. Metrics: MAE and RMSE (₹), signed bias, and WAPE =
Σ|error| / Σ actual (undefined, not infinite, when every actual is zero; never a per-month percentage error, which blows up at zero).
Verdicts need ≥ 6 scored forecasts and a 10 % MAE margin versus the best baseline. Horizons 1, 2, 3 and 6 are reported separately where samples exist.
Minimum sample sizes: a band needs 8 past errors; a verdict needs 6; fewer is reported as such.

**Synthetic results** (40 series × 24 months per pattern, pooled; run on 2026-10-25). Patterns: **STABLE** 30,000 ± 6 % noise; **TREND** 20,000 growing 3 % a month ± 4 %;
**STEP** 30,000 then 40,000 from month 12 ± 5 %; **SPIKY** 30,000 ± 5 % with a 15 % chance of a doubled month.

| Pattern | Horizon | n | Method MAE | Naive MAE | Mean-3 MAE | Method WAPE | Naive WAPE | Mean-3 WAPE |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| STABLE | 1 | 840 | 1,615 | 2,038 | 1,685 | 5.4% | 6.8% | 5.6% |
| STABLE | 3 | 760 | 1,643 | 1,941 | 1,617 | 5.5% | 6.5% | 5.4% |
| TREND | 1 | 840 | 1,271 | 1,506 | 1,829 | 4.3% | 5.0% | 6.1% |
| TREND | 3 | 760 | 1,892 | 2,659 | 3,378 | 6.2% | 8.7% | 11.0% |
| STEP | 1 | 840 | 2,995 | 2,388 | 2,351 | 8.4% | 6.7% | 6.6% |
| STEP | 3 | 760 | 4,245 | 3,310 | 3,338 | 11.7% | 9.1% | 9.2% |
| SPIKY | 1 | 840 | 6,652 | 9,380 | 8,802 | 19.2% | 27.0% | 25.4% |
| SPIKY | 3 | 760 | 6,638 | 9,222 | 8,652 | 19.0% | 26.4% | 24.8% |

Reading it honestly: the method beats both baselines on **TREND** and **SPIKY** (the level is robust to spikes) and beats "last month repeats" on **STABLE**, but is
**not better than the mean of the last three months at 3 months ahead on STABLE** (1,643 vs 1,617) and is **worse than both baselines after a level shift (STEP)**:
a 6-month level adapts slowly. Nothing was tuned to improve these numbers after they were first produced.

**Band coverage** (horizon 1, bands built only from errors available at each origin, 920 checks per pattern; nominal "about 80 %"): STABLE 79.5 %, SPIKY 77.7 %,
STEP 74.9 %, TREND 66.7 %. The band is therefore a description of past errors, weakest on trending series, and is never presented as a probability (KI-041).

**Limits of this evaluation:** artificial patterns written to probe the method; no seasonality, no irregular timing, no data errors; and the same author wrote the
generator and the method. It shows the *mechanics* behave as designed and where they fail (level shifts); it says nothing about accuracy on a real person's finances (KI-040).
