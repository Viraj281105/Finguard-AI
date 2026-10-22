# Testing

Results below are from a real run on **2026-10-22** (`.\scripts\test-all.ps1`, exit code 0, 363 automated tests). Re-run before editing
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

### Backend — 162 tests, 0 failures, 0 skipped

| Class | Tests | What it proves |
|---|---:|---|
| `BackendApplicationTests` | 3 | Whole app boots on real PostgreSQL; Flyway V1–V5 applied; the six tables exist; Hibernate `validate` passes |
| `migration.LegacyDatabaseAdoptionTest` | 3 | A pre-Flyway database with legacy rows is baselined and upgraded through V2–V5; lowercase email normalization; transaction dates backfilled; loan interest rate widened; V4 `upload_jobs` columns; **V5: free-text categories normalised without loss (`Food`→`DINING`, unknown text → `UNCATEGORIZED` + note, blank → null, existing categories marked `USER`), the new `CHECK` constraints reject inconsistent rows, `upload_jobs` classification defaults** |
| `ProfilesTest` | 4 | `dev` boots with defaults; `prod` refuses to start without `JWT_SECRET`; `prod` starts with one; `SecurityEnvironmentGuard` halts startup if dev key is used with non-local DB |
| `identity.AuthApiTest` | 18 | Register/login/refresh/logout contracts; 409 duplicate; field-level 400; min 10-char password (D-02); case-insensitive email normalization (D-05); refresh token rotation; token reuse detection revoking family (D-03b); 5 failed logins throttle 6th with 429 and `Retry-After: 60` (D-04); cookie-based refresh |
| `identity.JwtServiceTest` | 8 | Round trip; `typ` separation in both directions; expiry; foreign signature; garbage; unique tokens; weak/missing secret fails fast |
| `common.HttpErrorContractTest` | 6 | Framework errors return uniform `ApiError`: unknown route → 404, unsupported method → 405, unsupported media type → 415, unaccepted media type → 406; standard security headers present (`nosniff`, `DENY` frames, `strict-origin-when-cross-origin`, CSP) |
| `ledger.OwnershipApiTest` | 4 | For transactions, investments and loans: another user gets 404 on read/update/delete and sees nothing in lists; the owner is unaffected; unknown ids are 404 |
| `ledger.LedgerApiTest` | 13 | Decimal precision and response shape; transaction dates persisted; creation without date defaults to today; date update; pagination headers (`X-Total-Count`, `X-Page`, `X-Page-Size`, `X-Total-Pages`); query filtering (`from`, `to`, `type`, `category`); deterministic sorting (`date DESC, createdAt DESC`); query validation 400; loan defaults (active, 0% interest); optional investment value |
| `ledger.TransactionFingerprintTest` | 7 | Deterministic SHA-256 calculation; user isolation; date sensitivity; direction sensitivity; amount scale normalization; title whitespace normalization; intra-file occurrence index differentiation |
| `ingestion.parser.StatementParserRegistryTest` | 15 | Dynamic header-based detection and parsing for all 5 banks on synthetic fixtures, reordered headers, ICICI layout 2 (withdrawal/deposit), extra columns, diverse amount/date formats (parentheses, negatives, Dr/Cr suffixes, currency symbols), UTF-8 BOM, reconciliation invariant, and PII-sanitized row error snippet masking |
| `ingestion.ImportApiTest` | 13 | Upload → 202 → async completion → transactions persisted with real statement dates; reconciliation invariant validation; user job history `GET /api/upload/jobs` with strict isolation; Windows-1252 fallback decoding; binary null-byte rejection; repeated uploads skip duplicates with `duplicateRows` count; identical statements uploaded by different users isolate without collision; legitimate same-day identical transactions both import; unknown format → `FAILED`; non-CSV/empty/no-part → 400; unauthenticated → 401; AI-down fallback |
| `ingestion.ImportClassificationTest` | 10 | Predictions and **full provenance persisted** (source, confidence, calibration flag, rule, version, review flag); the AI service is sent direction and amount; **transfers, refunds, card payments and investments keep their amounts and directions**; invalid predictions (unknown category, expense category for a credit, `UNCATEGORIZED`, unknown method, legacy label) are ignored; **a throwing AI client never fails the import**; unavailable and partial outcomes are reported; **a category the user sets while classification runs is never overwritten**; **re-importing a statement keeps user categories and does not call the AI service again** |
| `ingestion.service.ImportRecoveryTest` | 1 | Interrupted jobs in `PENDING` or `PROCESSING` state are detected on application startup and transitioned to `FAILED` with safe re-upload instructions |
| `ingestion.web.UploadErrorApiTest` | 3 | Upload exceeding 5 MB limit returns 413 `ApiError` (`MaxUploadSizeExceededException`); empty upload returns 400 `ApiError`; executor queue saturation returns 503 `ApiError` with retry guidance |
| `ai.AiClientTest` | 17 | Request body (id, description, type, amount); full result mapping; unknown response fields ignored; long descriptions truncated; batching; one failed batch does not lose the others; 5xx / 401 / non-JSON / missing results → unavailable, never an exception; **invalid predictions dropped** (duplicate id, unknown id, blank category, confidence out of range, unknown or missing method, null); abstentions preserved; **stops after two consecutive failed batches**; success resets the failure count; **time budget** (fake clock); blank/null/repeated input ids; empty input; invalid properties rejected |
| `ai.AiClientHttpTest` | 5 | Real HTTP (JDK server): shared-secret header sent only when configured; **a slow service is cut off by the read timeout**; **a closed port** and an **HTML error page** degrade to unavailable |
| `ai.LiveAiServiceContractTest` | 4 | **The real FastAPI process**: `/health` capabilities; every recorded result from `contracts/classify-examples.json` parsed by the Java client; missing/wrong shared secret refused and handled; a 422 from the service degrades gracefully |
| `ingestion.LiveAiImportTest` | 1 | **Nothing mocked**: upload → import → HTTP → real classifier → database; provenance (`dining.swiggy`, `rules-1.0.0`, not calibrated) persisted; `ATM WDL` left uncategorised |
| `ledger.CategoryApiTest` | 13 | `PATCH …/category` records `USER`, clears automatic provenance, accepts any letter case and legacy labels, rejects unknown/blank/missing (400); **another user's transaction is 404**; authentication required; create/PUT validate and normalise categories (absent keeps, blank clears); `UNCATEGORIZED` and `needsReview` filters; **reclassify** categorises only uncategorised rows, never a user category, only the caller's rows, is harmless when the AI service is down or abstains, and makes no call when there is nothing to do |
| `ledger.TaxonomyContractTest` | 5 | Java enum, flows and directions equal `contracts/categories.json`; the V5 `CHECK` lists exactly the same ids; non-spending flows; parsing; an automatic category cannot pretend to be a user choice |
| `ArchitectureTest` | 9 | The dependency rules in [ARCHITECTURE.md](ARCHITECTURE.md#dependency-rules--enforced-by-architecturetest-archunit-9-rules), now including `categorization` |

### Frontend — 12 files, 80 tests, 0 failures (plus `tsc -b`, `eslint`, `vite build` clean)

`shared/lib/format`, `shared/auth/tokenStorage`, `shared/api/client` (bearer header; 401 handling; **silent refresh with queued request retry and in-flight coalescing**; logout on refresh failure; error-message selection), `shared/hooks/useResource`, `app/PrivateRoute`, `features/dashboard/summary`, `features/auth/AuthPages` (login, registration regression, duplicate and validation messages, in-flight state), `features/statement-import/CsvUpload` (file-type and size rejection, upload → poll → completion → refresh, duplicate reporting, reconciliation metrics breakdown [processed/duplicate/skipped/rejected], expandable row-level error inspector, failure, request error), `features/transactions/TransactionsPage` (loading, real date display, pagination controls and boundary checks, filtering, empty, error, delete confirm/cancel/failure, add via modal with date), `TransactionsPage.categories` (15: automatic vs user-confirmed vs missing categories; **confidence shown only when calibrated**; review flag and queue; inline correction limited to categories that suit the direction; one-click confirm; failure keeps the old category; Escape; "Needs a category" filter; categorize automatically — success, unavailable service, request failure, nothing to do; **totals exclude transfers, card payments, investments and refunds**; add-modal category options by type; unknown id fallback), `features/statement-import/UploadProgress` (5: categorisation notice for completed, unavailable, partial, not attempted / older server), `shared/categories` (4: **equals `contracts/categories.json`**, labels, direction filtering, totals rule), `features/dashboard/summary` (+ non-spending exclusion).

### AI service — 121 tests, 0 failures (plus `ruff`)

| File | Tests | What it proves |
|---|---:|---|
| `test_classifier.py` | 80 | **Every one of the 18 assignable categories** has ≥ 2 positive cases (50 narrations incl. credits/debits, UPI/NEFT/IMPS/POS styles); a test asserts every category is covered; 12 **unknown / ambiguous** narrations abstain with a reason (UPI to a person, unknown shop, ATM, gateways, empty); equal-weight rules for two categories are an ambiguity with alternatives; specific beats generic (instamart, amazon prime, school fees, card annual fee, education loan EMI); token-boundary matching (`rent` in `parental`); a credit from an expense merchant and a debit that says `salary`/`refund` are not asserted; card payments, refunds, transfers, investments in both directions; determinism; unique rule ids; the `ClassifierChain` lets a stub model complement the rules |
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
