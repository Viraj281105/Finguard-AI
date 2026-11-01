# 09 — Testing Strategy and Acceptance Criteria

Labels: **[V]** verified 2026-10-28 · **[E]** estimate/target · **[R]** recommendation. Acceptance criteria per project are in [07](07-project-catalog.md) and per roadmap item in [08](08-implementation-roadmap.md); this document defines *how* they are proven and the CI gates that keep them true.

## 1. Evidence standard

An item is **done** only when (1) its acceptance criteria are checked, (2) the command or CI run that proves each is recorded (commit SHA + run URL or log excerpt) in `docs/PROGRESS.md` or the relevant ADR, (3) tests exist for every behaviour change, (4) docs are updated, (5) rollback is documented. "It worked on my machine" without a recorded command is not evidence; neither is a number without its dataset, hardware class and commit.

**Performance claims** state: dataset (synthetic, seeded), hardware, warm-up, concurrency, percentile, commit SHA, tool version. Results live in `docs/performance/<date>-<topic>.md`. **No budget is published until measured** (already the repository's rule in `RELEASE_PLAN`).

## 2. Current baseline (fresh, 2026-10-28)

| Layer | Count / result | Notes |
|---|---|---|
| Backend | 387 tests, 0 failed/errored/skipped; instructions 92.6 %, branches 75.5 % (JaCoCo) | embedded PostgreSQL 15.19 **[V]** |
| Frontend | 150 tests in 19 files; `tsc`, `eslint`, `build` clean | jsdom only, no real browser **[V]** |
| AI | 128 tests; ruff clean; evaluation `--check` exit 0 | synthetic datasets (63 dev / 134 holdout) **[V]** |
| Cross-language | 5 live Java↔Python tests ran (not skipped) | **[V]** |
| Not covered | real browser e2e in CI; load; GitHub CI run; real bank files; accessibility audit; chaos beyond DB/AI outage | gaps closed by the items below |

## 3. Test pyramid and what is added

| Level | Tooling | Today | Adds (item) |
|---|---|---|---|
| Unit (pure functions) | JUnit 5, pytest, Vitest | strong | **property-based** tests for money invariants: jqwik (`netCashFlow = income + refunds − grossExpenses`, permutation invariance, scale ≤ 2, rounding idempotence), Go `rapid` in the reconciler (S2-02) |
| Integration (real DB) | `embedded-postgres` (no Docker needed) | strong | PG 18 binaries (S0-04); Testcontainers only for non-PG services (Kafka-class, Airflow DB) when they exist |
| API contract | MockMvc/HTTP tests | per-endpoint tests | OpenAPI snapshot + `oasdiff` (S1-03); response-snapshot comparison script for upgrades (S0-03) |
| Consumer/producer contract | live Java↔Python tests | good | event JSON Schema + backward-compat fixtures (S1-02); reconciler report schema |
| Architecture | ArchUnit (12 tests) | good | Spring Modulith `verify()` (S1-02) |
| Migration | `LegacyDatabaseAdoptionTest` | one scenario | **rolling-compatibility test:** boot image N-1 against schema N and run the smoke suite (S0-04/S3-03); migration lint; large-table lock-time measurement (S1-07) |
| Security regression | `AuthApiTest`, `ReleaseReadinessRemediationTest`, IDOR cases | strong for auth | authorization matrix over *every* endpoint; CSRF on cookie endpoints; RLS broken-repo test; JWT key rotation; upload fuzz; Airflow role write-denied test (S1-06, S2-03, Project H) |
| End-to-end (browser) | none in CI | gap | Playwright against compose stack: register → import → review → dashboard → forecast → scenario → recommendations (S0-08/S1-08) |
| Accessibility | none | gap | axe in Playwright; manual keyboard/screen-reader pass (S1-08) |
| Load/capacity | none | gap | k6: read mix, import mix, update-under-load (S1-01, S2-01, S3-03) |
| Resilience/chaos | DB-outage and AI-outage tests | partial | kill-the-process (outbox), kill-worker (durable import), Postgres restart, AI slow-response, queue saturation drills (S1-02, S3-05, S3-07) |
| Mutation testing | none | gap | PIT on `intelligence`/`forecasting` pure functions (report-only at first; gate at ≥ 70 % mutation score **[E]** after baseline) |
| Fuzz | none | gap | Go native fuzz for reconciler inputs and (if built) the parser; Python Hypothesis for the classifier normaliser |

## 4. CI gate matrix (target state)

| Gate | Blocks merge to `main` | First enforced |
|---|---|---|
| Backend tests + JaCoCo non-regression (instr. ≥ 90 %, branch ≥ 73 % **[E]** floor from current) | yes | S0-01 |
| Frontend `tsc`, `eslint`, tests, build | yes | exists |
| AI `ruff`, `pytest`, `evaluate --check`, provenance/hash check | yes | exists / S1-09 |
| Live Java↔Python contract tests not skipped | yes | exists |
| `npm audit --audit-level=high`, OSV (Maven/PyPI), Trivy on images (fixable high/critical) | yes | S0-01/S0-07 |
| CodeQL, gitleaks, actionlint/zizmor | yes | S0-07 |
| Lockfiles current; actions SHA-pinned | yes | S0-07 |
| OpenAPI breaking-change diff; generated client up to date | yes (label to override) | S1-03 |
| Event schema validity + backward fixtures | yes | S1-02 |
| Compose-booted smoke/e2e + a11y | yes | S0-08/S1-08 |
| Docs lint (no duplicate KI ids, stale-phrase list) | yes | S0-02 |
| SBOM + signature + provenance produced (release branch) | yes for releases | S3-02 |
| Nightly (not blocking): fuzz, mutation, k6 smoke, restore drill, reconciler on golden/synthetic ledger | alert on failure | S0-09, S2-02 |

## 5. Quantified acceptance targets (proposals to be confirmed after baselining)

| Area | Target | Measured by |
|---|---|---|
| Correctness | reconciler delta = exactly 0 daily; property tests 10,000 cases per invariant per run | S2-02 |
| Import reliability | 99 % of ≤ 5 MB statements terminal ≤ 60 s p95; zero duplicate rows under 20 concurrent overlapping uploads (existing audit scenario, now automated) | k6 + DB assertions |
| Read latency | p95 ≤ 500 ms for list/analytics/forecast at ≤ 10k tx/user; summary table (if adopted) ≤ 300 ms at 100k tx/user | k6 |
| Availability (staging soak) | ≥ 99.5 % over 14 days with 5 rps synthetic load | Prometheus |
| Event freshness | p99 ≤ 60 s publication→effect at 50 concurrent imports | metric |
| Security | 0 fixable high/critical findings on `main`; authorization matrix 100 % of endpoints; tamper/refusal tests for unsigned images | CI |
| Reproducibility | clean-machine `compose --profile full up --wait` ≤ 5 min; IaC re-apply `plan` empty; rebuild of derived data byte-identical | scripted checks |
| DR | RPO ≤ 5 min, RTO ≤ 2 h, restore + reconciler pass | quarterly drill |
| AI | no promotion without frozen real holdout; synthetic reports always stamped; rollback restores prior outputs bit-for-bit on fixtures | harness |

## 6. Rolling-deployment-safe schema checklist (applied to every migration PR)

1. Does the previous app version still work against the new schema? (expand-only)
2. Is the change online (no long lock)? New constraints `NOT VALID` → `VALIDATE`; indexes `CONCURRENTLY`.
3. Is there a backfill, and is it batched and resumable?
4. Is the destructive "contract" step in a *later* release?
5. Is the recovery plan in the migration header and rehearsed on staging with 10× synthetic data?
6. Do ORM `validate` and `LegacyDatabaseAdoptionTest` still pass?

## 7. Benchmark methodology (so numbers are comparable)

Fixed synthetic dataset generator (seeded, versioned); three sizes (1×, 10×, 100× the audit's 10,000-row ledger); warm-up 60 s; steady-state 5 min; report p50/p95/p99, error rate, throughput, CPU, RSS/heap, DB connections, GC pauses; run three times and report the median; record `git rev-parse HEAD`, JDK/Go/Python versions, host class. Compare *before/after* on the same host class within one session.

## 8. Test data policy

Synthetic only in dev, CI, staging and evaluation (principle 13). Real, owner-consented, anonymised statements (D-06) are stored outside Git in access-controlled object storage, used only for the frozen holdout, never in logs, CI artifacts or fixtures. A CI rule rejects commits adding files under `ai/evaluation/data/real*`.

## 9. How the plan itself is measured

| Plan metric | Target |
|---|---|
| Items completed vs estimate | track actual days; re-estimate Stage N+1 after Stage N (expect ±30 %) |
| WIP | never > 2 |
| Open P0/P1 known issues | 0 at Stage 0 exit |
| Mean time to detect a seeded outage (drill) | ≤ 5 min after S3-04 |
| Mean dependency age | < 30 days behind latest patch after S0-07 |
