# 07 — Project Catalog

Each project states the business problem, architecture, technologies, dependencies, complexity, measurable acceptance criteria and portfolio value. Complexity is **S** (≤ 5 days) · **M** (6–12) · **L** (13–25) · **XL** (> 25) of engineering days **[E]**. Roadmap IDs refer to [08](08-implementation-roadmap.md). Labels as in [01](01-current-state-assessment.md).

| Project | Verdict | Stage |
|---|---|---|
| A — Go ingestion gateway | **Gate first** (benchmark → decide); extraction deferred unless gate fires | 2 (S2-01), conditional S2-06 |
| B — Event-driven financial intelligence | **Do, without a broker** (Spring Modulith outbox) | 1 (S1-02), 2 (S2-05) |
| C — Airflow data engineering | **Pilot**, isolated | 2 (S2-03) |
| D — Kubernetes platform engineering | **Local pilot; production deferred** | 3 (S3-06) |
| E — Production-grade observability | **Do** | 1 (S1-01), 3 (S3-04) |
| F — AI evaluation and model governance | **Do** | 1 (S1-09), 2 |
| G — Analytics architecture | **Do the reconciler + summary table; defer ClickHouse** | 2 (S2-02, S2-04) |
| H — Security and supply-chain hardening | **Do** | 0 (S0-07), 3 (S3-02) |
| I — Platform currency (new) | **Do first** | 0 (S0-03, S0-04) |
| J — Privacy lifecycle and tenant isolation (new) | **Do** | 1 (S1-05, S1-06) |
| K — Reproducible delivery pipeline (new) | **Do** | 0 (S0-05, S0-08), 3 (S3-03) |

---

## Project A — Go ingestion gateway

**Business problem.** Statement files are untrusted user input. Today they are parsed inside the same JVM that holds database credentials and the whole ledger. Is that a performance or a security problem?
**Evidence.** Performance: **not demonstrated** — 10,000 rows imported in 9.9 s including classification **[H]**, upload cap 5 MB **[V]**, import pool 2–5 threads/queue 10 **[V]**. Parser memory: the CSV is held in memory (≤ 6 MB) **[V]**. **Security isolation:** a real but modest argument — a parser bug in the JVM sits next to the ledger. **Does the Java pipeline need extraction? Not for load. It might for isolation, and certainly if PDF/OCR statements arrive.**
**Proposed architecture (only if the gate fires).** A Go worker `statement-normalizer`: input = file bytes (from the API via a bounded stream or object storage), output = normalized rows (JSON Lines) validated against the same parser contract; **no DB credentials**; strict limits (max size, max rows, max line length, per-file CPU/time budget, bounded memory via streaming `bufio.Scanner` with explicit buffer cap); idempotency key = SHA-256 of file content; backpressure through a bounded work queue and `429/503 + Retry-After`; outputs are deterministic so Java can compare results during a shadow-run.
**Gate (S2-01 decides, by measurement).** Build the benchmark harness first (k6 or Go) against the existing Java pipeline with synthetic statements at 1×/10×/100× today's size and concurrency 1/5/20. **Extract only if** (a) p95 import time > 60 s or heap > 70 % of limit at the target concurrency **after** straightforward Java tuning, **or** (b) a PDF/OCR/binary format is accepted, **or** (c) a security review requires parser isolation. Otherwise record the numbers in an ADR and stop — that *is* a successful outcome of the project.
**Technologies.** Go 1.27, `encoding/csv`, `net/http`, `slog`, testcontainers-go (only if DB is involved), `go test -fuzz`, k6.
**Dependencies.** S1-01 (traces/metrics to measure), S1-03 (contract), S0-05 (images).
**Complexity.** Benchmark S–M (S2-01, 4 d); full worker L (S2-06, ~15 d).
**Acceptance criteria.**
* Benchmark report with p50/p95/p99, throughput, peak RSS/heap, GC pauses for Java at the listed loads, reproducible by one command and stored in `docs/performance/`.
* If extracted: Go parser output equals the Java parser output on **all** fixture statements and 10,000 fuzz-generated files (differential test); malformed/oversize inputs are rejected with bounded memory (RSS ≤ 128 MB at 100 MB hostile input); no goroutine leaks (`goleak`); 5 MB statement p95 < 1 s; cut-over behind a flag with shadow comparison for 2 weeks and zero diffs.
**Portfolio value.** Go, concurrency and bounded-resource design, fuzzing, load testing — *and* the maturity to measure before extracting.
**Risk.** Duplicating parser rules in two languages (drift). Mitigation: shared fixtures and differential tests; extract the *whole* parser, not part.

## Project B — Event-driven financial intelligence

**Business problem.** Derived data (analytics, reviews, audit, deletion fan-out) is currently recomputed on request or not at all; there is no audit trail and no feedback loop from user corrections to model governance.
**Architecture.** Spring Modulith event publication registry (outbox) → in-process idempotent listeners with an inbox. Events: `import.completed`, `ledger.period_changed`, `transaction.category_reviewed`, `account.deletion_requested` ([05 §3](05-data-and-event-architecture.md#3-event-catalog-and-schemas)). Consumers: audit-writer, analytics-refresh (S2), review-metrics (S2). **No Kafka.**
**Technologies.** Spring Boot 4.1, Spring Modulith 2.1, PostgreSQL 18, JSON Schema, Flyway.
**Dependencies.** S0-03; S1-01 for lag metrics.
**Complexity.** M (S1-02 6 d) + M (S2-05 5 d).
**Acceptance criteria.**
* Rollback test: a ledger write that rolls back leaves **no** event; a committed write leaves exactly one per listener.
* Kill-the-process test: kill the JVM between commit and delivery; after restart all events are delivered (≥ 1×) and the inbox yields exactly one effect.
* Duplicate-delivery test (same `event_id` 100×) → one effect.
* Replay test: truncate `analytics`, replay → byte-equal result to a freshly computed one; reconciler delta 0.
* p99 publication→completion ≤ 60 s under 50 concurrent imports; dead-letter alert fires in a drill.
* No narration/PII in any payload (schema test: only allowed fields).
**Portfolio value.** Transactional outbox/inbox, idempotent consumers, replay semantics, schema evolution — the distributed-systems core, honestly scoped.

## Project C — Airflow-based data engineering

**Business problem.** Evaluation, data-quality checks, reconciliation and reports are run by hand or in CI; there is no scheduled, observable, re-runnable pipeline, and `ai/evaluation` has no dataset/version lineage.
**Architecture.** Airflow 3.3.x via a compose profile `airflow` (API server, scheduler, DAG processor, triggerer if needed, LocalExecutor, own PostgreSQL database). **DAGs (≥ 4):**
1. `synthetic_data_generate` — seeded generator → versioned CSV + manifest.
2. `classifier_evaluate` — run the evaluation on frozen datasets → `metrics.json`/report with `SYNTHETIC` provenance, coverage-vs-accuracy curve, adversarial set; fails on regression vs the last promoted baseline.
3. `ledger_data_quality` — SQL checks on a read-only role (constraints, direction/category, date bounds, import reconciliation identity).
4. `analytics_reconcile_and_export` — invokes the Go reconciler; exports month aggregates to Parquet (`DECIMAL(38,2)`); publishes a report.
5. (later) `drift_report` — weekly review/correction rates from `category_reviewed` events.
**Isolation.** See [06 §6](06-security-and-reliability.md): read-only role, separate metadata DB, no API path; DAG failures cannot affect live ledger operations; **Airflow is never in the synchronous transaction path.**
**Technologies.** Airflow 3.3.2, Python 3.14, TaskFlow API, `pytest` for DAG integrity, Parquet (PyArrow) / DuckDB for offline checks.
**Dependencies.** S1-09 (evaluation harness), S2-02 (reconciler) for DAG 4, S0-05 (compose).
**Complexity.** L (10 d).
**Acceptance criteria.**
* `docker compose --profile airflow up` to a healthy scheduler in < 10 min on a clean machine; teardown leaves no volumes with ledger data.
* DAG integrity test in CI (import all DAGs, no cycles, no top-level DB access); each DAG idempotent on re-run for the same logical date (byte-equal outputs, tested).
* Backfill of 30 days of `ledger_data_quality` succeeds and is reproducible.
* Security regression test: the Airflow DB role **fails** `INSERT/UPDATE/DELETE` on `public.*`; stopping Airflow for 24 h has no effect on API SLOs.
* Failure injection: kill a task mid-run → retry succeeds; alert on 2 consecutive failures.
* Exit criterion recorded in ADR-009 (remove if < 4 active DAGs for 2 quarters).
**Portfolio value.** Orchestration, scheduling, idempotent batch design, data quality, lineage by manifest, backfills.
**Honest caveat.** For ≈ 5 nightly jobs a cron/CI schedule would work; Airflow is chosen for the skill and the growth path, and is wrapped so it can be removed without touching the product.

## Project D — Kubernetes platform engineering

**Business problem.** No deployment topology exists beyond Compose; resource limits, probes, secret handling, network policy, autoscaling and safe migrations are undesigned.
**Architecture.** Helm chart (or Kustomize base + overlays) for `backend`, `ai`, `frontend`, optional `airflow`; Deployments with requests/limits, readiness/liveness/startup probes (Actuator groups), PodDisruptionBudgets, HPA on CPU + request latency, default-deny NetworkPolicies (only `ingress → frontend/backend`, `backend → ai`, `backend → postgres`), External Secrets (or sealed secrets) for config, a **pre-install/upgrade Job** running Flyway with the DDL role, ingress with TLS (cert-manager), PostgreSQL **outside** the cluster (managed) or a single-instance StatefulSet for the local pilot only. Local: `kind`/`k3d`. **Non-Kubernetes development workflow remains the default** ([03 §8](03-target-architecture.md)).
**Technologies.** Kubernetes 1.36/1.37, Helm 4, Kustomize, ingress-nginx or Gateway API implementation, cert-manager, External Secrets, metrics-server; Argo CD/Flux only with a real cluster.
**Dependencies.** S0-05 (images), S1-01 (probes/metrics), S3-02 (signed images), S3-03 (migration Job).
**Complexity.** L (12 d pilot); production adoption only through the gate in [03 §9](03-target-architecture.md).
**Acceptance criteria.**
* One command creates the cluster, installs the chart, runs the Playwright smoke suite green, and one command tears it down, leaving nothing.
* Rolling update with `maxUnavailable=0` shows **zero** failed requests in a k6 run at 20 rps during the update; a bad image triggers automatic rollback (readiness failure halts rollout).
* NetworkPolicy test: a debug pod outside the allowed set cannot reach postgres/ai (connection refused/timeout), proven by an automated test.
* Pod killed during load → SLO-1 unaffected; memory limit OOM-kill is detected by an alert.
* Every cluster component has a one-line justification in the chart README; none is installed "just in case".
**Portfolio value.** Kubernetes fundamentals applied with discipline; honest comparison with the simpler platform.

## Project E — Production-grade observability

**Business problem.** No health endpoint, metrics, traces or structured logs (W-02). Reliability cannot be defined or measured.
**Architecture.** Boot Actuator (health groups, Prometheus registry), Micrometer; OpenTelemetry (Boot 4 starter or Java agent) and OTel Python for FastAPI; W3C trace-context propagated SPA → API → AI (the browser sends `traceparent` via the API client); JSON logs with `trace_id`, `span_id`, `request_id`, user hash (never email), redaction filters; local single-container LGTM-style stack under compose profile `obs`; Grafana dashboards and alert rules **as code**; SLO dashboards for SLO-1…5.
**Technologies.** Micrometer, OTel 2.x, Prometheus 3.13 LTS, Grafana 13.2, Loki, Tempo, Alertmanager.
**Dependencies.** S0-03 (starter), S0-05.
**Complexity.** M (S1-01 6 d) + M (S3-04 5 d).
**Acceptance criteria.**
* `/actuator/health/readiness` returns 503 when the DB is down and 200 when back within 10 s (test); `/actuator/*` not reachable through the public port.
* One import produces **one trace** spanning API → worker → AI service, visible in Tempo, with Loki logs correlated by `trace_id`.
* Redaction test: after importing a fixture statement, grep of logs/spans/metrics finds **no** narration text, e-mail, or full user id.
* SLIs for SLO-1…5 are queryable; a synthetic outage (stop Postgres) fires the burn-rate alert within the window; alert → runbook link.
* Overhead: p95 latency regression ≤ 5 % and CPU ≤ +10 % with tracing at 10 % sampling (k6 A/B).
**Portfolio value.** Practical SRE: SLIs/SLOs, burn-rate alerts, tracing across languages, privacy-safe telemetry.

## Project F — AI evaluation and model governance

**Business problem.** The classifier is rules-only, evaluated on synthetic data the rule author wrote (100 % on 63 dev / 134 holdout rows **[V]**) — a ceiling, not an estimate. There is no real data, no calibration, no drift signal and no promotion policy.
**Architecture.** (1) **Dataset registry**: manifest per dataset (`data_provenance`: `synthetic` | `real-consented`; SHA-256; row count; label procedure; frozen-holdout flag); (2) **evaluation harness** producing coverage-versus-accuracy curves (accuracy of asserted labels at each confidence tier, abstention rate), per-category precision/recall, confusion matrix, **adversarial suite** (homoglyphs, truncation, mixed case, reference-number noise, merchant look-alikes, direction mismatches, prompt-like strings), and Expected Calibration Error once confidences are calibrated; (3) **promotion policy** (below); (4) **drift monitoring** from review events; (5) **model/rule versioning** via `rules-x.y.z`/`model_version` stored per transaction **[V]** and a config-switchable `ClassifierChain`; (6) **real data path**: the owner's *own* anonymised statements, labelled by the owner, never committed to Git (object storage, access-controlled), used only for a frozen real holdout.
**Promotion criteria (candidate vs current).** All must hold on the **frozen real holdout** (n ≥ 500 labelled transactions **[E]** — below that, report intervals and promote nothing on accuracy grounds): precision of asserted labels ≥ current; coverage not lower by > 2 pts or abstention deliberately raised with justification; no category precision below its floor; adversarial suite no worse; 2-week shadow mode with correction rate ≤ current; rollback verified. **Synthetic performance is never evidence of real-world accuracy** and every artifact is stamped accordingly.
**Technologies.** Python 3.14, stdlib `csv` + `statistics` (add scikit-learn only when a model exists), `pytest`, Airflow DAG 2, Parquet/JSON manifests.
**Dependencies.** S1-09 (harness), S2-03 (scheduling), owner-provided real data (D-06 — an external blocker).
**Complexity.** M (S1-09 5 d); ongoing.
**Acceptance criteria.**
* `evaluate --check` fails if a dataset hash differs from its manifest, if dev/holdout overlap, or if a report lacks a provenance stamp.
* Reports include the coverage-vs-accuracy table and an adversarial section; CI uploads them; synthetic reports titled `SYNTHETIC —`.
* A deliberately bad rule change (e.g., broad `upi` rule) is **caught** by the gate (mutation-style test).
* Rollback drill: switching `classifier.chain` to the previous version restores prior outputs on the fixture set bit-for-bit.
**Portfolio value.** ML governance without ML hype: evaluation discipline, calibration, abstention, drift, honest reporting.

## Project G — Analytics architecture and reconciliation

**Business problem.** All analytics are computed in Java per request; correctness is proven by unit tests and a manual audit, not by an independent continuous check. At scale a derived store may be needed, but must never diverge from the ledger.
**Architecture.** Go **reconciler** (independent verifier, [04 §6](04-service-boundaries-and-contracts.md)) + `analytics.monthly_summary` derived table (rebuildable, refreshed by the event consumer) + staged escalation ladder L0→L4 ([05 §5](05-data-and-event-architecture.md#5-analytics-architecture--when-postgresql-is-enough)) + offline DuckDB/Parquet analysis. Golden dataset with hand-verified aggregates shared by Java and Go tests.
**Technologies.** Go 1.27, PostgreSQL 18, DuckDB 2.0 (offline, pinned), Parquet, Airflow DAG 4.
**Dependencies.** S1-02 (events), S2-03 (scheduling optional), S0-05.
**Complexity.** M (S2-02 8 d) + M (S2-04 6 d).
**Acceptance criteria.**
* Reconciler returns exit 0 on the golden dataset and on a 1,000-user × 24-month synthetic ledger; **exactly zero** delta for income, expenses, refunds, net cash flow, savings rate.
* Fault injection: modify one `analytics` row by 1 paisa → reconciler reports it with user, month, delta; fix by rebuild → zero.
* Property-based tests (jqwik in Java; `testing/quick` or rapid in Go): `netCashFlow = income + refunds − grossExpenses`; permutation of rows yields identical aggregates; refunds across months preserve the identity (**regression of R-5**).
* Load: summary table makes dashboard p95 ≤ 300 ms at 100k tx/user synthetic (otherwise L1 is not adopted).
* Decision record: a documented "PostgreSQL is sufficient until X" with the measured X; ClickHouse only if L4 trigger fires.
**Portfolio value.** Data correctness engineering, independent verification, decimal-exact pipelines, honest scale reasoning.

## Project H — Security and supply-chain hardening

**Business problem.** CI lacks automated dependency/secret/code scanning, SBOMs and signed artifacts; actions are tag-pinned; `npm audit` is non-blocking and currently reports a high (W-04/W-05).
**Architecture.** [06 §10](06-security-and-reliability.md): lockfiles+hashes, SHA-pinned actions with minimal permissions, Renovate, CodeQL, OSV/Trivy, gitleaks, Syft SBOM, cosign keyless signing + provenance attestation, digest-pinned non-root images, threat-model document and abuse-case regression tests (authorization matrix, CSRF on cookie endpoints, rate-limit bypass attempts, JWT `alg=none`/tamper cases already tested **[H]**, upload fuzz, SSRF absent by design).
**Technologies.** GitHub Actions, CodeQL, Renovate/Dependabot, gitleaks, Trivy/OSV-Scanner, Syft, cosign 3.x.
**Dependencies.** S0-05 for image scanning; S3-02 for signing.
**Complexity.** M (S0-07 3 d) + M (S3-02 5 d).
**Acceptance criteria.**
* Main branch CI fails on: fixable high/critical vuln (npm/PyPI/Maven/image), a committed secret (seeded canary test), an unpinned action, an out-of-date lockfile.
* Every release image has an SBOM, a verifiable signature and provenance; `cosign verify` is part of the deploy script and a tampered image is refused (test).
* Abuse-case suite (≥ 25 cases) runs in CI; includes cross-tenant read/write for **every** endpoint (generated from the controller list).
* Renovate PRs merge automatically for green patch updates; mean age of dependencies < 30 days behind the latest patch.
**Portfolio value.** Practical DevSecOps with measurable gates.

## Project I — Platform currency (new, evidence-based)

**Business problem.** W-01 (Boot 3.5 out of OSS support), W-06 (Python 3.12/3.14 skew, no lock, PG 15 vs 18).
**Scope.** Spring Boot 3.5.11 → 4.1.x on JDK 25; PostgreSQL 15 → 18 (compose + embedded test binaries 15.19 → 18.x + Flyway); Python 3.14 in Docker/CI + hashed lock; Node stays 24 LTS.
**Dependencies.** None (do first). **Complexity.** M–L (S0-03 6 d, S0-04 3 d).
**Acceptance criteria.** 387 backend / 150 frontend / 128 AI tests green unchanged; OpenAPI/`API.md` behaviour byte-identical for the compatibility surface (response snapshot comparison script over all documented endpoints); `ProfilesTest` and the prod guard green; `LegacyDatabaseAdoptionTest` green on PG 18; restore rehearsal on 18; no new Spring deprecation warnings left unresolved (or recorded).
**Portfolio value.** Disciplined major-version migration with a regression net.

## Project J — Privacy lifecycle and tenant isolation (new)

**Business problem.** No account deletion/export/audit trail (W-09); application-only tenant isolation (W-10).
**Scope.** Account export (JSON + CSV with formula-neutralisation), ordered deletion with grace period, append-only `audit_log`, Row-Level Security pilot, authorization-matrix test, JWT `kid` rotation.
**Dependencies.** S1-02 (events), S0-03. **Complexity.** M+M (S1-05 7 d, S1-06 5 d).
**Acceptance criteria.** Delete test populates every table for two users and verifies zero residual rows for the deleted user and **no change** for the other; export is complete (row counts equal) and contains only that user's data (cross-tenant test); audit rows exist for login, export, delete, category review; RLS exit criteria in [06 §9](06-security-and-reliability.md#9-row-level-security-design-s1-06-pilot).
**Portfolio value.** Privacy engineering and defence-in-depth.

## Project K — Reproducible delivery pipeline (new)

**Business problem.** No backend/frontend image; staging only on Windows PowerShell and not reproducible (W-03); CI unproven (W-04).
**Scope.** Multi-stage BuildKit Dockerfiles (backend JRE image, frontend static image), compose `full` profile, compose-based staging, CI job booting the stack for smoke/e2e, first green GitHub run, `Idempotency`/health, later CD with migration step and rollback.
**Dependencies.** S0-03/04 for the final images (Dockerfiles can start earlier). **Complexity.** M+M (S0-05 4 d, S0-08 4 d, S3-03 6 d).
**Acceptance criteria.** From a clean checkout on Linux and Windows, `docker compose --profile full up --wait` → all healthy ≤ 5 min; Playwright smoke green; images non-root, read-only rootfs capable, < 250 MB backend / < 60 MB frontend **[E]**; image built once in CI and the digest promoted; rollback to previous digest verified.
**Portfolio value.** Reproducibility, container craft, release engineering.

---

## 10. Portfolio matrix — what each project proves

● = substantial, hands-on proficiency · ◐ = meaningful supporting exposure · blank = not demonstrated.

| Project | Java | Go | Python | Distributed systems | Data engineering | Kubernetes | Security | Observability | Cloud-native deployment |
|---|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|
| A — Go ingestion (gate/worker) | ◐ | ● | | ◐ | | | ◐ | ◐ | |
| B — Event-driven intelligence | ● | | | ● | ◐ | | ◐ | ◐ | |
| C — Airflow | | | ● | ◐ | ● | | ◐ | ◐ | |
| D — Kubernetes | | | | ◐ | | ● | ◐ | ◐ | ● |
| E — Observability | ◐ | | ◐ | ◐ | | ◐ | ◐ | ● | ◐ |
| F — AI evaluation/governance | | | ● | | ◐ | | ◐ | ◐ | |
| G — Analytics + reconciler | ◐ | ● | | ◐ | ● | | | ◐ | |
| H — Security/supply chain | ◐ | ◐ | ◐ | | | ◐ | ● | | ◐ |
| I — Platform currency | ● | | ◐ | | | | ◐ | | |
| J — Privacy/RLS | ● | | | ◐ | | | ● | ◐ | |
| K — Delivery pipeline | ◐ | | ◐ | | | ◐ | ◐ | ◐ | ● |

**Reading it honestly.** Java depth comes from B, I, J (not from adding Java for its own sake). Go is real in A (benchmark/decision) and G (reconciler). Distributed-systems proficiency is credible via B (outbox/inbox/replay) without a broker; Kafka would add breadth but is deliberately gated. Kubernetes and cloud-native deployment are demonstrated by D and K with explicit trade-off reasoning (managed platform vs cluster), which is the stronger signal than a cluster running four containers.
