# 02 — Technology Landscape and Decision Matrix

**Research date: 2026-10-28.** Versions were read on 2026-10-27/28 (see §9 for each source and its reliability).
Labels: **[V]** verified fact · **[E]** estimate · **[A]** hypothesis · **[R]** recommendation (see [01](01-current-state-assessment.md)).
Adoption stages: **Adopt** (do it, in the stage given) · **Pilot** (time-boxed, with exit criteria) · **Defer** (a named trigger must occur first) · **Reject** (no justified use in this product).

> **Reading guide.** "Latest stable" below is the newest *non-pre-release* seen on the date above. Where the only source is an aggregator (endoflife.date, GitHub "latest" tag) the version is marked **†** and must be re-checked against the vendor page on the day an upgrade is actually made. Pre-release or brand-new majors are labelled explicitly.

## 1. Decision matrix

| Technology | Latest stable (date) † = aggregator-only | Intended responsibility in FinGuard-AI | Problem solved | Why preferable to alternatives | Integration complexity | Operational burden | Security / compatibility concerns | Stage | Objective adoption criteria |
|---|---|---|---|---|---|---|---|---|---|
| **Java JDK 25 (LTS)** | 25 GA 2025-09-16; JDK 27 GA 2026-09-14 is *non-LTS*; Oracle free (NFTC) updates for 25 to Sep 2028 | Runtime for the authoritative financial domain | Repo is on JDK 21; 25 is the current LTS | LTS cadence + virtual threads already usable; staying on 21 is fine short-term but moves the cliff | Low (Boot 4.1 supports it; compile on 25, keep `release 21` until Boot upgrade lands) | None extra | Check Lombok/JaCoCo/ArchUnit/Mockito compat on 25 | **Adopt** (S0-03) | All 387 backend tests green on JDK 25; no deprecation errors; CI matrix runs 21 and 25 once |
| **Spring Boot 4.1.x** | **4.1.2 (2026-10-22)** †; 4.0.9 (OSS ends 2026-12-31); 3.5.16 (OSS ended 2026-06-30); repo is on 3.5.11 | Application framework | Out-of-support line; needed for Modulith 2, Spring Framework 7, Security 7, built-in OTel starter | Staying on 3.5 = unpatched; 4.0 expires in 2 months → go to 4.1 | **High** (Jackson 3, Spring Security 7, Hibernate 7, modular starters) but the suite (387 tests, 92.6 % instr.) is a strong net | Low once done | Spring Security 7 + JWT filter and cookie behaviour must be re-verified by `AuthApiTest`/`E2eAuditRegressionApiTest` | **Adopt** (S0-03, P1) | 387 tests green; contract tests unchanged; API snapshot byte-identical; startup guard (`ProfilesTest`) green |
| **Spring Modulith** | **2.1.2 (2026-10-23)** † (2.x targets Boot 4 — confirm in the release notes at adoption) | Module verification + in-process domain events with JDBC *event publication registry* (a transactional-outbox implementation) | Gives the outbox without a broker; replaces hand-written ArchUnit module rules with `@ApplicationModule` verification | Same JVM, same transaction, zero new infrastructure; Kafka-free path to events | Medium | Low (one table, one purge job) | Event table holds payloads → must contain IDs, not PII | **Adopt** (S1-02) after Boot 4.1 | Event published in same tx as ledger change (proved by a rollback test); incomplete publications are re-submitted at restart; module verification in CI |
| **Go** | **1.27.3 (2026-10-19)**; 1.27.0 2026-08-19; 1.26.10 supported until 1.28 | Narrow, independently justified components: reconciliation tool (S2-02), optionally a statement-parse worker (S2-06) | CPU/memory-bounded I/O workers, single static binary, strong ops tooling story | Beats Java for tiny sidecars (start-up, image size) and Python for concurrent I/O; **loses** to Java for anything touching ledger invariants | Medium (second toolchain, CI job, image) | Medium (one more build/patch surface) | Standard-library `crypto/x509` security patches shipped 2026-10-19 — stay on latest patch | **Pilot** (Stage 2) | A named problem the Java service demonstrably does not solve (see Project A gate) |
| **Python 3.14** | **3.14.10 (2026-10-25)**; 3.15.2 (2026-10-25) is new; 3.13 active support ended 2026-10-01; 3.12 security-only to 2028-10 | AI classification, evaluation, data-pipeline code | Currently 3.12 in image/CI, 3.14.7 locally | Align everything on 3.14 (active support to 2027-10, security to 2030-10); do not jump to 3.15.x yet | Low | None | Pinned hash lock (`uv`/`pip-tools`) required | **Adopt** (S0-04) | Image, CI and local on same minor; lockfile with hashes; tests green |
| **TypeScript** | **7.0.2 (2026-08-20)** † (native compiler major); repo on ~5.9.3 | Web app + generated API types | Type safety end to end | Generated types from OpenAPI remove hand-copied DTO types | Medium for TS 7 (tooling compat: typescript-eslint, Vite, Vitest) | None | None | **Pilot** TS 7 on a branch (S1-08); keep 5.9 until parity proven | `tsc` clean, eslint clean, same 150 tests, build time and bundle unchanged |
| **React / Vite / Node** | React **19.3.1** (2026-10-14); Node **24.22.1 LTS** (security-only from 2026-10-20, EOL 2028-04-30), Node 26 becomes LTS 2026-10-28; Vite: a newer major than the repo's 7.x appears to exist (create-vite 9.3.3 on 2026-10-27) — verify | SPA | — | Stay on Node 24 until 26 has had a month of LTS patches | Low | None | `npm audit` high (`zod`) to fix now | **Adopt** patch upgrades (S0-07); defer majors | Audit clean at `--audit-level=high`; same test counts |
| **Rust** | **1.99.1 (2026-10-15)** † | Candidate for a CPU-bound parser | No measured hotspot exists | Only if PDF/OCR or a large-file parser appears, and then Go/Java libraries must be compared first | High | High (third toolchain) | Memory-safe by construction (genuine plus for untrusted-input parsing) | **Reject for now** | A profile shows parsing > 50 % of p95 import time *and* Go/Java fixes are exhausted *or* untrusted binary formats are accepted |
| **PostgreSQL 18** | **18.6**; 17.11; 16.15; 15.19 (EOL 2027-11-11); **19 is RC1 (2026-10-15), not GA** | Authoritative system of record | Repo runs 15 (compose) and 15.19 (tests) | Move now, before any real data exists, via dump/restore; 18 supported to 2030-11-14 | Low–medium (compose image, embedded-postgres binaries, Flyway) | Low | Test on 18 *and* stay compatible with 16/17 syntax for managed-service portability | **Adopt** (S0-04). **Defer 19** | Full suite green on 18.6 binaries; V1–V7 apply on empty 18 DB; restore rehearsal |
| **Flyway** | **13.11.1 (2026-10-22)** † (repo: 11.7.2 via Boot) | Migrations | — | Already in place; keep | Low (Boot BOM manages) | None | `baseline-on-migrate` stays only for legacy adoption | **Adopt** (via Boot 4.1 BOM) | `LegacyDatabaseAdoptionTest` green |
| **Valkey** | **9.1.3 (2026-10-26)** † (Redis-compatible, BSD) | Only: shared rate-limit counters + access-token deny-list when ≥2 backend replicas | O-02/O-10 | Postgres-backed limiter is the first alternative (no new system); Valkey if latency matters | Low | Medium (a stateful service) | Never a system of record; no financial data | **Defer** | ≥2 backend replicas in a real environment, **and** a measured need that a PG table cannot meet |
| **Apache Kafka** | **4.4.0 (2026-10-22)**; 4.3.2; 4.2.2 (KRaft only) | Event backbone | Not needed today | Mature ecosystem, schema registry, replay | High | **High** (cluster, upgrades, ACLs, retention) | ACLs, PII in topics | **Defer** (gate in [05](05-data-and-event-architecture.md) §7) | ≥2 independently deployed consumers, or sustained > 500 events/s, or required multi-day replay by a separate team |
| **Redpanda** | **v26.2.2 (2026-08-22)** † | Kafka-API alternative | — | Single binary, easier ops; license is source-available (verify current terms) | Medium | Medium | License/vendor terms | **Defer** (only as a Kafka *implementation*, same gate) | Same as Kafka |
| **RabbitMQ** | **4.4.0 (2026-10-16)**; 4.3.7 | Task-oriented messaging | Background jobs | A Postgres job table (`SELECT … FOR UPDATE SKIP LOCKED`) already covers import jobs | Medium | Medium | — | **Reject** now | A job type needing priorities/routing that PG cannot give |
| **Apache Pulsar** | **5.0.1 (2026-10-26)** † | Multi-tenant streaming | — | Complexity unjustified (no geo-replication/tiered-storage need) | High | High | — | **Reject** | Never expected |
| **Apache Airflow** | **3.3.2 (2026-09-17)** (3.3 released 2026-07-06; 3.2/3.1/3.0 EOL) | Scheduled batch workflows: synthetic data, evaluation, data-quality, reconciliation, aggregates | Dependency-managed, observable, re-runnable batch | Industry standard; DAG versioning and event-driven scheduling exist in 3.x. **Honest note:** the workload is ~5 nightly jobs; cron + CI would suffice — Airflow is justified by learning/portfolio value and by growing job count | Medium | **Medium–High** (scheduler, API server, DB, workers) | Own metadata DB; **read-only** DB role to ledger; never in the write path | **Pilot** (S2-03) | ≥4 DAGs with dependencies; run history shows reproducibility; teardown/start from compose in < 10 min |
| **Dagster** | **1.13.27 (2026-10-26)** † | Alternative orchestrator | Asset-centric lineage | **Technically the closer fit** for "dataset → evaluation → report" assets; Airflow chosen on market weight (ADR-009) | Medium | Medium | — | **Defer** (alternative) | Switch if asset lineage becomes the main need |
| **dbt-core** | **1.12.6** † (date unverified) | SQL transforms in a warehouse | No warehouse exists | Plain SQL views are enough on PG | Medium | Medium | — | **Defer** | A separate analytical store exists with > 20 models |
| **DuckDB** | **2.0.0 (2026-10-21)** † — a **brand-new major**, treat as pilot-quality | Offline analytics on Parquet exports | Reproducible local analysis | Zero server; fits Airflow tasks | Low | None | New major: pin exact version, read-only use | **Pilot** (S2-04, offline only) | Aggregates from Parquet equal PG aggregates to the paisa |
| **Polars** | **2.1.0** † | DataFrame ops in pipelines | — | Standard-library `csv` suffices today (eval is 197 rows) | Low | None | — | **Defer** | Dataset > 1 M rows or heavy joins |
| **Apache Spark** | not researched in depth | — | Terabyte-scale processing | No dataset within 3 orders of magnitude | High | High | — | **Reject** | > 1 TB analytical data |
| **ClickHouse** | LTS **26.8 (2026-08-27)**, 26.3 LTS (supported to 2027-03-26) † | Analytical store | Not needed | PG with summary tables is enough until criteria | Medium | High | — | **Defer** | Analytical queries p95 > 2 s on summary tables with > 200 M rows |
| **Object storage (S3-compatible)** | n/a | Backups, immutable import artifacts (if retained), Parquet exports | Backup durability; DPDP-minimising retention policy | Managed bucket on the chosen cloud | Low | Low | SSE-KMS, lifecycle, no public access | **Adopt** (S3-01 for backups) | Restore drill from bucket; lifecycle deletes verified |
| **pgvector / vector DB** | n/a | — | No semantic-retrieval requirement | — | — | — | — | **Reject** | A documented RAG use case with an evidence corpus |
| **OpenTelemetry** | Java instrumentation **2.33.1 (2026-10-26)** †; Boot 4 ships `spring-boot-starter-opentelemetry` | Traces, metrics, logs across services | W-02 | Vendor-neutral; Boot 4 starter or Java agent | Low–medium | Low | Redact PII in span attributes/logs | **Adopt** (S1-01) | Trace spans from browser request → backend → AI service in one trace; no narration text in telemetry (test) |
| **Prometheus** | **3.13.5 LTS (EOL 2027-07-31)**; 3.15.1 (EOL from 2026-11-06) | Metrics storage/alerts | — | Take the **LTS** line | Low | Low–medium | — | **Adopt** (S1-01 local, S3-04 prod) | SLI queries return data; burn-rate alert fires in a drill |
| **Grafana** | **13.2.5 (2026-10-27)** † | Dashboards | — | — | Low | Low | Auth on, no anonymous access | **Adopt** | Dashboards provisioned as code |
| **Loki / Tempo** | not version-checked in this research | Logs / traces backends | — | Grafana LGTM image for local dev; managed or self-hosted later | Low locally | Medium in prod | Retention vs privacy | **Adopt locally (Stage 1), decide for prod in Stage 3** | Same-trace log correlation works |
| **OpenSearch** | **3.9.0 (2026-09-29)** † | Log search / security analytics | — | Loki covers log needs | High | High | — | **Reject** | SIEM requirement |
| **Kubernetes** | **1.37.1** (EOL 2027-10-28); **1.36.5**; 1.35.9; 1.34 EOL 2026-10-27 | Orchestrator | Not required for 4 containers | Managed container platform (ECS/Cloud Run/Container Apps class) is cheaper to operate | High | **High** | NetworkPolicies, PSS, RBAC | **Pilot** locally (S3-06); production **Defer** | Gate in [03](03-target-architecture.md) §9 |
| **Helm / Kustomize** | Helm **4.3.1 (2026-10-15)** † | Packaging | — | Kustomize overlays are enough for 4 services | Low–medium | Low | Chart provenance | **Pilot** with K8s | — |
| **Argo CD / Flux** | Argo CD **3.5.6 (2026-10-20)** †; Flux **2.10.0 (2026-10-26)** † | GitOps | — | Only meaningful with K8s | Medium | Medium | Cluster-admin scope | **Defer** (with K8s) | Cluster exists |
| **OpenTofu / Terraform** | OpenTofu **1.13.2 (2026-10-21)** †; Terraform 1.16.7 (2026-10-22) | IaC | No IaC | OpenTofu: MPL-2.0 licence, Terraform-compatible; pick one and record in ADR | Medium | Low | State encryption, least-privilege deploy role | **Adopt** (S3-01) | `plan` is empty on a re-run; teardown works |
| **Testcontainers (Java)** | **2.0.5 (2026-04-20)** † | Integration tests needing real services (Kafka-class, Airflow DB) | Embedded PG covers today's needs without Docker | Keep `embedded-postgres` for the DB; add Testcontainers only where a service is genuinely needed | Low | None (needs Docker in CI) | — | **Adopt selectively** | First test that needs a non-PG service |
| **Cosign / Trivy / Syft** | Cosign **3.1.4 (2026-10-20)** †; Trivy **0.76.1 (2026-10-21)** † (release notes mention CA-trust-store mitigations — not independently verified) | Signing, scanning, SBOM | W-04 | De-facto OSS toolchain; GitHub attestations as alternative | Low | Low | Pin tool versions by digest; scanners are themselves supply-chain targets | **Adopt** (S0-07 scan+SBOM; S3-02 signing) | Release image has signature + SBOM + provenance; CI fails on critical/high with a fix available |
| **OIDC / OAuth 2.1** (Keycloak etc.) | n/a | External IdP | First-party SPA with own auth is acceptable | A self-hosted IdP adds an HA, patched, stateful system for no product gain | High | High | — | **Reject** now; revisit for B2B/SSO | A second client app or enterprise SSO |
| **Passkeys / WebAuthn** | Spring Security has built-in WebAuthn support (verify exact version at adoption) | Phishing-resistant sign-in/MFA | Password-only today | Highest security gain per unit of complexity | Medium | Low | Recovery flow design | **Pilot** (S2-07) | Registration/login/recovery tests pass; fallback documented |

## 2. Languages evaluated against concrete workloads

| Workload | Java (Boot) | Go | Python | TypeScript | Rust |
|---|---|---|---|---|---|
| Ledger writes, budgets, goals, auth (invariants, transactions) | **Best — keep.** Types, JPA, `BigDecimal`, 387 tests, ArchUnit | Would duplicate the domain | No | No | No |
| Statement parsing (≤ 5 MB, ≤ 10,000 rows; **[H]** 10,000 rows imported in 9.9 s incl. classification) | **Adequate** — parser is not the bottleneck | Wins only on isolation/footprint (a sandboxed untrusted-file parser with no DB credentials) | Slower, no gain | — | Wins only if binary formats (PDF) arrive |
| Classification | Calls it | — | **Best** (ecosystem for evaluation and, later, models) | — | No |
| Forecasting/scenarios (≤ 12 points per series) | **Best** — must share exact financial definitions (D-13) | No | Unneeded network hop | — | No |
| Reconciliation/verification tooling (read-only, independent of the app's own code paths) | Possible | **Best fit:** independent implementation is the point; single binary; easy CI use | Good | — | Overkill |
| Event relay (outbox → broker), only if a broker exists | Spring Modulith / Debezium | Strong fit (tiny relay) | Possible | — | Overkill |
| Data pipelines, evaluation, notebooks | — | — | **Best** | — | No |
| Web UI + typed API client | — | — | — | **Best** | WASM not warranted |
| Load/benchmark harness | — | **Good** (k6 is Go; or custom) | Locust | — | — |

**Rule:** a language is added only for a workload where the table shows a column that is *better than Java for a concrete reason*, and only inside a project with acceptance criteria ([07](07-project-catalog.md)). Today that is **Python (already present) and Go (reconciler first)**. Rust is rejected until a measured hotspot or untrusted binary parsing exists.

## 3. Category notes

### A–B. Languages, backend and API architecture
* **Modular monolith vs microservices → modular monolith.** Extraction criteria are already written in `ARCHITECTURE.md §7` (measured resource conflict, independent release cadence, different runtime, separate team). None is met except *different runtime* for the AI service — already extracted.
* **Spring Modulith** gives module verification and the event publication registry; keep ArchUnit rules that Modulith cannot express (controllers↔repositories).
* **Contracts:** OpenAPI generated from the controllers (committed snapshot, `oasdiff` breaking-change check in CI, TypeScript types generated from it); FastAPI already emits OpenAPI for the AI contract, backed by `contracts/*.json` and the live contract tests. **gRPC: reject** — two internal services, one RPC, JSON over HTTP with a shared secret is adequate; gRPC adds tooling without removing a problem.
* **Resilience:** `AiClient` already has connect/read timeouts, a 90 s budget and fail-fast after two failed batches **[V]** (`application.properties`). Add idempotency keys for create/upload endpoints and keep retries to idempotent calls only. A circuit breaker on the AI client is *nice but low value*: the import already degrades gracefully.
* **API versioning:** URL stays `/api/…`; additive changes only until 1.0; Spring Framework 7 supports API-version mapping if a v2 is ever needed **[A, verify]**.

### C. Streaming and async
**Answer to "does FinGuard-AI need a streaming platform?" — not now; later only if the gate in [05 §7](05-data-and-event-architecture.md) fires; possibly never.** Reasons: event volume is bounded by one human per ledger (**[E]** tens of events per user per month plus bursts of one `ImportCompleted` per statement); consumers are in-process (derived analytics, notifications); a Postgres outbox gives durability, ordering per aggregate and replay by id/time range without a broker. Kafka's value (many independent consumers, long retention, stream processing) has no consumer today.

### D. Data engineering and orchestration
Orchestration ≠ streaming ≠ job queue ≠ request path: Airflow runs *scheduled batch* DAGs outside the request path; the import queue stays an application job table; request-time analytics stay in Java. **Airflow must never be a dependency of the synchronous transaction path** — enforced structurally: separate metadata DB, read-only ledger role, no network route from the API to Airflow ([06 §6](06-security-and-reliability.md)).

### E. Cloud-native
Compare K8s with a managed container platform on four containers + a managed PostgreSQL: the platform wins on operations (no control plane to patch, built-in rolling deploys, scale-to-zero for staging). Kubernetes is recommended **as a local/pilot skill-building target with real manifests** and as a production option only when the gate in [03 §9](03-target-architecture.md) fires.

### F. Databases
PostgreSQL stays the *only* system of record. Redis/Valkey, ClickHouse and object storage are never sources of truth; every derived store must be reproducible from PostgreSQL and reconciled against it (Project G).

### G. Observability
OpenTelemetry for instrumentation (Boot 4 starter for the JVM; OTel Python for FastAPI), Prometheus (LTS) + Grafana + Loki + Tempo through the `otel-lgtm` style single container locally. Reliability objectives are defined in [06 §4](06-security-and-reliability.md), not "install dashboards".

### H. AI and financial intelligence
Deterministic rules stay first; a model is appended to the existing `ClassifierChain` only after (a) ≥ N real, labelled, owner-consented transactions exist, (b) it beats the rules on a *frozen real* holdout, (c) calibration is measured. **RAG, vector search and LLM-written explanations are rejected for now** — there is no evidence corpus and no requirement; if explanations are ever added: numbers are rendered from Java objects, the LLM supplies wording only, and an automated check rejects any output whose numerals do not match the provenance object ([06 §7](06-security-and-reliability.md)).

### I. Security and governance
Genuine requirements: tenant isolation tests, secret hygiene, dependency/supply-chain controls, account deletion/export, audit trail, backups with restore drills, TLS/HSTS, mandatory internal service credential. Optional enterprise features: external IdP/OIDC, mTLS mesh, SIEM, HSM-backed keys.
**India:** the Digital Personal Data Protection Act 2023 and Rules 2025 (notified 2025-11-13) are being phased in — consent-manager provisions around **2026-11-13** and most data-fiduciary duties around **2027-05-13**; the older SPDI Rules continue meanwhile. Sources were law-firm commentary (§9), not the Gazette. **This is engineering input, not legal advice;** obtain counsel before processing anyone else's financial data. Account Aggregator (RBI) integration is a possible future data source and is explicitly *out of scope* here.

### J. Developer productivity
Keep GitHub Actions. Add: lockfiles, build caching, contract tests, Testcontainers where needed, Playwright e2e against a Compose-booted stack, preview environments only after the Stage-3 platform exists, Renovate/Dependabot with grouped updates and a documented merge policy. A Kubernetes-free developer workflow is mandatory ([03 §8](03-target-architecture.md)).

## 4. Compatibility constraints worth recording

| Constraint | Consequence |
|---|---|
| Spring Boot 4 uses Jackson 3 and Spring Security 7 | JWT filter, cookie handling and JSON date formats need regression verification (existing tests cover them) |
| Spring Modulith 2.x targets Boot 4 **[A]** | Do not adopt before S0-03 |
| PostgreSQL 15 binaries in tests (`embedded-postgres-binaries 15.19.0`) | Bump to 18 with the compose image, or tests and production diverge |
| Python 3.14 free-threaded/JIT features are irrelevant here | Use plain CPython 3.14, no experimental flags |
| Node 24 is LTS now; Node 26 LTS begins 2026-10-28 | Move after one month of 26.x LTS patches |
| Kafka 4.x is KRaft-only | No ZooKeeper anywhere in any future plan |
| Airflow 3.0–3.2 are EOL | Only 3.3.x is acceptable |
| K8s supports the three newest minors; 1.34 went EOL yesterday | A pilot starts on 1.36 or 1.37, never older |

## 5. Pre-release / experimental items (explicitly labelled)

PostgreSQL 19 (RC1) — **defer**; DuckDB 2.0.0 and TypeScript 7 (new majors) — **pilot only, pinned**; Python 3.15 — **defer**; Kubernetes 1.37.0-rc builds seen in search snippets are superseded by 1.37.1 and must not be used.

## 6. Version freshness policy [R]

Renovate/Dependabot weekly; patch updates auto-merge when CI is green; minor/major updates need a changelog read; a quarterly "support window" review lists every runtime and its end-of-support date (generated from endoflife.date) so no component silently ages out (the exact failure found in W-01).

## 7. Portfolio-oriented skill matrix

See [07-project-catalog.md §10](07-project-catalog.md).

## 8. Open verification items (do before adopting)

1. Confirm Spring Boot 4.1.2 is GA and Modulith 2.1.x's Boot baseline on spring.io (the project page showed 4.1.2 without a GA label).
2. Confirm Loki/Tempo versions and licences on grafana.com.
3. Confirm Redpanda's current licence, only if the Kafka gate ever fires.
4. Confirm which Node 26.x release the project would target.

## 9. Sources (accessed 2026-10-27/28)

| Topic | Source | Reliability |
|---|---|---|
| Go releases | <https://go.dev/doc/devel/release> | Primary |
| Kubernetes supported versions | <https://kubernetes.io/releases/> | Primary |
| PostgreSQL versions, PG 19 RC1 | <https://www.postgresql.org/support/versioning/> | Primary |
| Spring Boot current version | <https://spring.io/projects/spring-boot> (shows 4.1.2, no GA label) | Primary, partial |
| Spring Boot support dates | <https://endoflife.date/api/v1/products/spring-boot/> | Aggregator |
| Kafka releases | <https://kafka.apache.org/community/downloads/> | Primary |
| Airflow cycles | <https://endoflife.date/api/v1/products/apache-airflow/> | Aggregator |
| Python, Node, RabbitMQ, Grafana, ClickHouse, Prometheus, React, Terraform, OpenSearch, OpenTofu, Argo CD, Valkey, Rust | `https://endoflife.date/api/v1/products/<name>/` | Aggregator |
| Redpanda, Dagster, DuckDB, Helm, Flux, OTel Java, Testcontainers, TypeScript, Spring Modulith, Flyway, Vite, Cosign, Trivy, Pulsar | `https://api.github.com/repos/<org>/<repo>/releases/latest` | Primary tag, "latest" may not equal "most important" |
| Polars, dbt-core | `https://pypi.org/pypi/<name>/json` (version only; upload date not read) | Primary |
| JDK 25 LTS / JDK 27 GA | InfoQ Java news (2026-04-20, 2026-09-14); Oracle Java download page; OpenJDK projects page | Secondary/primary mix |
| OTel Spring Boot 4 starter | opentelemetry.io blog; third-party tutorials | Secondary |
| DPDP Rules 2025 timeline | Law-firm and press summaries (Deccan Herald, Mondaq, S.S. Rana, EY, Legal500) | Secondary — verify against the Gazette notification |
