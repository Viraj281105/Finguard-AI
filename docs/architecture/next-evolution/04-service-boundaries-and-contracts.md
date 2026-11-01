# 04 — Service Boundaries and Contracts

Contract policy for every interface in [03](03-target-architecture.md). Labels: **[V]** verified · **[A]** hypothesis · **[R]** recommendation.

## 1. Interface catalog

| # | Interface | Producer → Consumer | Style | Contract artifact (source of truth) | Auth | Status |
|---|---|---|---|---|---|---|
| I-1 | Public REST API | Spring Boot → SPA | JSON over HTTPS | `docs/API.md` today → **OpenAPI 3.1 snapshot generated from controllers** (`contracts/openapi/finguard-api.json`) | Bearer access JWT; refresh via HttpOnly cookie | exists; snapshot S1-03 |
| I-2 | Classification | Spring Boot → AI service | JSON over HTTP, `POST /v1/classify`, `GET /health` | `contracts/categories.json`, `contracts/classify-examples.json`, FastAPI OpenAPI | `X-Internal-Token` (shared secret, constant-time compare **[V]**); **mandatory outside dev** (S0-06) | exists |
| I-3 | Domain events | Spring Boot (outbox) → in-process listeners | Java application events persisted in the publication registry | `contracts/events/*.schema.json` + Java records | n/a (same process) | S1-02 |
| I-4 | Reconciliation report | Go reconciler → CI/Airflow/alerts | JSON file + exit code + metrics | `contracts/reconcile/report.schema.json` | Read-only DB role | S2-02 |
| I-5 | Evaluation artifacts | AI evaluation harness → Git / object storage | `metrics.json`, `report.md`, dataset manifest | `ai/evaluation/` + `contracts/evaluation/manifest.schema.json` | n/a | exists; extended S1-09 |
| I-6 | Analytics export | Airflow task → object storage (Parquet) | Files | schema in `contracts/analytics/*.json` | Read-only role; write to bucket only | S2-04 |
| I-7 | Telemetry | Services → OTel Collector | OTLP | OTel semantic conventions + redaction policy | network-restricted | S1-01 |
| I-8 | Browser ↔ server session | SPA ↔ API | Cookie + header | `docs/API.md` auth section | see [06 §2](06-security-and-reliability.md) | exists |

## 2. Module catalog (backend)

| Module | Public surface (what other modules may call) | Owns tables | Must not depend on |
|---|---|---|---|
| `common` | error types, config | — | any module |
| `identity` | `User` reference, security config | `users`, `refresh_tokens` | any business module |
| `ledger` | `TransactionService`, `LoanService`, `InvestmentService` | `transactions`, `loans`, `investments` | ingestion, ai, categorization, intelligence, budgeting |
| `ingestion` | `ImportService` | `upload_jobs` | — (uses ledger, categorization) |
| `categorization` | `CategorizationService` | (writes ledger via `TransactionService.applyAutomaticCategories`) | ingestion |
| `intelligence` | `AnalyticsService`, `RecommendationService` | — (derived views in `analytics` schema, S2) | — |
| `budgeting` | `BudgetService`, `GoalService` | `budgets`, `financial_goals`, `goal_contributions` | — |
| `forecasting` | `ForecastingService`, `SavedScenarioService` | `saved_scenarios` | ingestion, ai, categorization |
| `ai` | `AiClient` | — | business modules |
| **`events`** (S1) | `DomainEvent` types, publisher | event publication registry | business modules (publishers depend on it, not vice-versa) |
| **`privacy`** (S1) | `AccountLifecycleService` (export, delete) | `audit_log`, `deletion_requests` | — (listens to events; calls module deletion hooks) |

**Enforcement:** keep today's 12 ArchUnit tests; add Spring Modulith `ApplicationModules.verify()` (S1-02) and an explicit allowed-dependencies table in the test. A new module needs an owner section in `docs/ARCHITECTURE.md`, a `README` per module and tests (principle 9).

## 3. REST API contract policy

1. **Compatibility surface** is the list in [01 §8](01-current-state-assessment.md). Until v1.0.0 the rule is *additive only*: new optional request fields, new response fields, new endpoints. Removing/renaming a field, changing a type, tightening validation, or changing an HTTP status is a **breaking change**.
2. **Source of truth:** controllers + DTO records generate the OpenAPI document at build time; the snapshot is committed. CI runs a breaking-change diff (`oasdiff`) between the PR snapshot and `main`; a breaking diff fails unless the PR carries the label `api-breaking` and a CHANGELOG/ADR entry.
3. **TypeScript client:** types are generated from the snapshot (`openapi-typescript`) into `frontend/src/shared/api/generated/`; hand-written `types.ts` files shrink to view-model types. The frontend build fails if a generated type changed without regeneration (`git diff --exit-code`).
4. **Versioning:** URLs stay `/api/…`. A v2 only if a breaking change is unavoidable; then serve v1 and v2 in parallel for ≥ 2 minor releases with a `Deprecation`/`Sunset` header. Spring Framework 7 API-versioning features may be used **[A — verify at adoption]**.
5. **Error contract:** keep the uniform `ApiError` body; add a stable machine-readable `code` field (additive) and a `traceId` (from OTel) so support can correlate a user report to a trace.
6. **Idempotency:** `Idempotency-Key` header on `POST /api/transactions`, `/api/loans`, `/api/investments`, `/api/budgets`, `/api/goals`, `/api/upload/csv`. Server stores `(user_id, key, request_hash, response_status, response_body, created_at)` for 24 h; same key + same body → replay stored response; same key + different body → `422`; concurrent same-key requests → second waits on a row lock. Imports are *already* content-idempotent via fingerprints; the header protects against double-click and retry on non-import creates.
7. **Pagination/limits:** list endpoints default to `size=20` with `X-Total-*` headers exposed via CORS (regression-tested **[V]**); add a hard max `size=200`.
8. **Timeouts/backpressure:** import queue full → `503` + `Retry-After` **[V — UploadErrorApiTest]**; per-request server timeout 30 s; request body cap 6 MB **[V — multipart config]**.
9. **Authorization checks are part of the contract:** every endpoint is listed in an authorization matrix test (S1-06): *an authenticated user can never read or mutate another user's resource; other users' ids return 404*.

## 4. AI-service contract (I-2)

Current (v1, **frozen** — compatibility surface): request items `{description, type, amount}` (≤ 500 items, ≤ 1000 chars each); response per item `{category, confidence, confidence_calibrated, method, rule_id, needs_review, reason?, alternatives?}` plus `rules-1.0.0`-style version string; `GET /health` with `capabilities`.

Evolution rules:
* **Additive only inside `/v1`**; new model versions change `method`/`model_version` values, never the shape.
* **A model never replaces rules silently:** it is appended to `ClassifierChain` after the rules and only for rule abstentions (existing design **[V — ARCHITECTURE §5]**); every response states which link answered.
* **Promotion** of a new classifier version requires the gate in [Project F](07-project-catalog.md#project-f--ai-evaluation-and-model-governance) and is reversible by configuration (`classifier.chain`), not a redeploy of the backend.
* **Resource isolation:** the service has no database credentials and no outbound internet; memory/CPU limits are set per container; the backend treats it as optional (imports complete when it is down **[V]**).
* **Auth:** `X-Internal-Token` required whenever `AI_ENV != dev` (service refuses to start otherwise); token rotated by dual-accept window (`AI_INTERNAL_TOKEN` + `AI_INTERNAL_TOKEN_PREVIOUS`).

## 5. Event contracts (I-3)

Defined in [05 §3](05-data-and-event-architecture.md#3-event-catalog-and-schemas). Policy: JSON Schema per event under `contracts/events/`, `schema_version` in every envelope, additive-only within a version, CI test that serialises each event and validates it against its schema, and a backward-compatibility test that replays last release's stored payload fixtures into the current deserialiser.

## 6. Go reconciler contract (I-4)

* **Input:** read-only DSN (role `finguard_reconciler`, `SELECT` on `transactions`, `analytics.*`, `upload_jobs`, `event_publication`); period selector (`--user`, `--month`, or all).
* **Checks:** (1) per user-month `income`, `expenses`, `refunds`, `netCashFlow`, savings rate recomputed from `transactions` equal the analytics tables and, on a sample, the live API; (2) invariant `rows_processed + duplicate_rows + rejected_rows + skipped_rows = total_rows` per job; (3) no `transactions` row violates direction/category compatibility; (4) fingerprint uniqueness holds; (5) orphaned outbox publications older than threshold; (6) every amount has scale ≤ 2.
* **Output:** `report.json` (schema-validated) with per-check `status`, `delta` (decimal string, not float), `samples`; exit code 0 only if all deltas are exactly zero; Prometheus textfile metrics (`finguard_reconcile_delta_total`, `finguard_reconcile_last_success_timestamp`).
* **Independence rule:** it must **not** import any shared code with the Java definitions; correctness of the *definition* is established by a shared golden dataset (`contracts/golden/ledger-golden.json` with hand-verified aggregates) used by **both** test suites.

## 7. Service-to-service authorization

| Hop | Now | Target |
|---|---|---|
| Backend → AI | optional shared secret **[V]** | **mandatory** secret (S0-06), dual-accept rotation, secret from a secret manager; private network only; optionally mTLS where the platform provides it |
| Backend → PostgreSQL | one app role | **three roles**: `finguard_app` (DML, owns migrations via a separate `finguard_migrator` DDL role), `finguard_reconciler`/`finguard_airflow` (SELECT only, specific tables/views). App role cannot `DROP`/`ALTER` in production |
| Airflow → data | n/a | read-only role + exports; API has no inbound route from Airflow; Airflow has no write role on `public` |
| Operator → everything | n/a | SSO/MFA on the cloud console; deploy role assumed by CI via OIDC federation, no long-lived cloud keys |

## 8. Frontend contract

Typed client generated from OpenAPI; route-level lazy loading (bundle 488 kB today); error rendering uses `ApiError.code`; e2e tests (Playwright) own the *user-visible* contract (registration → import → review → dashboard → forecast); the SPA never computes money (verified by removal of `computeSummary` in v0.6.0 **[H]**).

## 9. Ownership

Each component has an owner (initially the same person), a `README.md` with run/test/health instructions, a runbook entry ([06 §5](06-security-and-reliability.md)), and an acceptance checklist in [09](09-testing-and-acceptance-criteria.md). New components without those four items are not merged (principle 9).
