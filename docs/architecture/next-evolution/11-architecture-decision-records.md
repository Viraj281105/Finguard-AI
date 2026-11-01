# 11 — Architecture Decision Records (proposed)

All records are **Proposed** as of 2026-10-28; none is Accepted until the project owner signs it. On acceptance, port the decision into `docs/DECISIONS.md` (which uses `D-xx` identifiers and records open owner decisions D-01…D-14; **D-11, the deployment target, is directly relevant to ADR-011 and ADR-010**). Format: Context → Decision → Alternatives considered → Trade-offs → Revisit when.

---

## ADR-001 — Keep the modular monolith; no microservice extraction now
**Context.** Modules are ArchUnit-enforced, share one transaction boundary and financial definitions; one engineer owns everything. **Decision.** Remain a single Spring Boot deployable plus the already-extracted AI service. **Alternatives.** Extract ingestion/analytics/identity as services; Go rewrite of hot paths. **Trade-offs.** Gives up independent scaling of modules; avoids distributed transactions, duplicated rules, and operational overhead. **Revisit when** any of the four conditions in `ARCHITECTURE.md §7` is *measured*: resource conflict, independent release cadence, different runtime, separate team.

## ADR-002 — Move to Spring Boot 4.1.x on JDK 25 (LTS)
**Context.** Boot 3.5 left OSS support on 2026-06-30 (aggregator data; spring.io lists 4.1.2 as current); Boot 4.0 OSS support ends 2026-12-31; repo is 3.5.11 on JDK 21. **Decision.** Upgrade directly to 4.1.x and compile on JDK 25. **Alternatives.** Patch to 3.5.16 and stay (unsupported); go to 4.0 (expires in two months); stay on JDK 21 (acceptable short-term). **Trade-offs.** One large migration (Jackson 3, Security 7, Hibernate 7) cushioned by 387 tests; unlocks Modulith 2 and the OTel starter. **Revisit when** 4.1 shows regressions affecting auth/CORS (fallback: 3.5.16 interim while no production exists).

## ADR-003 — PostgreSQL is the only system of record; upgrade to 18
**Context.** Compose and tests run PG 15 (final release 2027-11-11); 18.6 is current (supported to 2030-11-14); 19 is RC1. **Decision.** Standardise on PG 18; derived stores must be rebuildable and reconciled. **Alternatives.** Stay on 15; wait for 19; add a second OLTP store. **Trade-offs.** One-time dump/restore now is cheapest while no production data exists. **Revisit when** a managed provider lacks 18 or 19 reaches GA with a feature this product needs.

## ADR-004 — Domain events through a PostgreSQL outbox (Spring Modulith); no broker yet
**Context.** Need audit trail, derived-data refresh and deletion fan-out; all consumers in-process; event rate ≈ 0.06/s even at 10k active users **[E]**. **Decision.** Use Modulith's JDBC event publication registry as a transactional outbox with an inbox table for consumers; JSON Schema contracts in Git. **Alternatives.** Kafka/Redpanda, RabbitMQ, Pulsar, Debezium CDC, hand-rolled outbox. **Trade-offs.** No cross-process fan-out or long retention; in exchange: zero new infrastructure and exactly-once *effects* through idempotent, recompute-based consumers. **Revisit when** the streaming gate in [05 §7](05-data-and-event-architecture.md#7-the-streaming-platform-gate-decision-not-now-maybe-later-possibly-never) is met (≥ 2 of 5 conditions, measured).

## ADR-005 — Go: reconciler first; ingestion extraction only behind a measured gate
**Context.** Import of 10,000 rows took 9.9 s including classification **[H]**; no demonstrated CPU/memory conflict; parser isolation is a modest security benefit. **Decision.** Introduce Go for (a) an independent read-only reconciler, (b) a benchmark harness; build the Go statement worker only if the S2-01 benchmark or a new binary-format requirement justifies it. **Alternatives.** Go ingestion now; Rust parser; keep Java only. **Trade-offs.** Adds a second backend toolchain (CI, images, patching) for a narrow, valuable job. **Revisit when** S2-01 results are in, or PDF/OCR statements are accepted.

## ADR-006 — Identifier and timestamp conventions: legacy preserved, new tables modern
**Context.** Existing tables use `varchar(255)` UUID strings and zone-less `timestamp` **[V]**; rewriting is risky and gives users nothing. **Decision.** Do not migrate existing columns; new tables use `uuid` and `timestamptz`; the mixed convention is documented. **Alternatives.** Full migration to `uuid`/`timestamptz`. **Trade-offs.** Permanent inconsistency vs avoided risky rewrites. **Revisit when** a table is rebuilt for another reason (partitioning at L2 is the natural moment).

## ADR-007 — Tenant isolation: application scoping + RLS backstop (pilot) [Accepted]
**Context.** Isolation relies on every repository call adding `user_id` **[V]**; IDOR tests passed **[H]**. **Decision.** Add an authorization-matrix test for every endpoint, dual-key JWT rotation with `kid` headers, and pilot PostgreSQL RLS with `SET LOCAL` per transaction. See full record in [ADR-007-tenant-isolation-and-key-rotation.md](ADR-007-tenant-isolation-and-key-rotation.md). **Alternatives.** Schema-per-tenant; database-per-tenant; trust app code only. **Trade-offs.** RLS adds per-transaction overhead and plan risk; per-tenant schemas explode migrations. **Revisit when** pilot misses its exit criteria (revert) or B2B multi-tenancy (organisations) appears (then tenant model changes).

## ADR-008 — Analytics stays in PostgreSQL along a measured escalation ladder
**Context.** Audit: analytics < 340 ms at 10k rows **[H]**. **Decision.** L0→L4 ladder in [05 §5](05-data-and-event-architecture.md#5-analytics-architecture--when-postgresql-is-enough); ClickHouse only at L4; every derived store reconciled to zero delta. **Alternatives.** Warehouse now (ClickHouse/BigQuery), dbt, Spark. **Trade-offs.** Defers analytical features; avoids a second store to reconcile. **Revisit when** a trigger in the ladder fires with measurements.

## ADR-009 — Airflow 3.3 as a pilot orchestrator, isolated; Dagster as the alternative
**Context.** Real batch needs: synthetic data, evaluation, quality checks, reconciliation, exports. Airflow 3.3.2 is the only maintained 3.x line. **Decision.** Pilot Airflow in a compose profile with a read-only role, separate metadata DB, no API path. **Alternatives.** Dagster (asset-centric — technically a closer fit), cron + CI, Prefect. **Trade-offs.** Honest: Airflow is chosen for market-standard skills and growth path, not necessity; cost is a scheduler + DB to run. **Revisit when** fewer than 4 active DAGs for 2 quarters (remove) or asset lineage becomes the main need (switch to Dagster).

## ADR-010 — Infrastructure as code with OpenTofu
**Context.** No IaC yet; Terraform 1.16.7 and OpenTofu 1.13.2 are both current; OpenTofu is MPL-2.0 and Terraform-compatible. **Decision.** OpenTofu with remote encrypted state and OIDC-federated CI. **Alternatives.** Terraform, Pulumi, cloud-native CDK. **Trade-offs.** Smaller vendor ecosystem than HashiCorp's; avoids licence concerns. **Revisit when** a required provider/feature is unsupported or the target cloud's official tooling dominates.

## ADR-011 — Production on a managed container platform; Kubernetes as a gated alternative
**Context.** Four stateless containers + managed PG; single operator. **Decision.** Default to a managed container platform; build Kubernetes manifests and a local pilot; adopt K8s in production only if ≥ 3 of the five conditions in [03 §9](03-target-architecture.md#9-kubernetes-vs-a-managed-container-platform--the-gate) hold. **Alternatives.** K8s now; single VM + Compose. **Trade-offs.** Less portability and ecosystem exposure in production; far lower operational cost and risk. **Revisit when** the gate fires. Depends on owner decision D-11.

## ADR-012 — Observability with OpenTelemetry and the Grafana stack
**Context.** None exists (W-02). **Decision.** OTel instrumentation, Prometheus (LTS), Grafana, Loki, Tempo; managed vs self-hosted decided by cost at Stage 3. **Alternatives.** Vendor APM (Datadog etc.), OpenSearch stack, Actuator-only. **Trade-offs.** Assembly effort vs vendor lock-in/cost. **Revisit when** telemetry cost or toil exceeds the budgets in [10](10-cost-and-operational-model.md).

## ADR-013 — Rust rejected for now
**Context.** No measured hotspot; no untrusted binary parsing. **Decision.** Do not introduce Rust. **Alternatives.** Rust parser/reconciler. **Trade-offs.** Forgo memory-safety and speed benefits that nothing here needs; avoid a third toolchain. **Revisit when** a profile shows parsing dominating p95 *and* Go/Java fixes are exhausted, or PDF/binary formats are accepted.

## ADR-014 — No external OIDC provider now; pilot passkeys
**Context.** First-party SPA, single client, stateless JWT + rotating refresh cookie **[V/H]**. **Decision.** Keep the in-house auth; pilot WebAuthn passkeys (Spring Security) for phishing-resistant sign-in. **Alternatives.** Keycloak/Auth0/Cognito; TOTP only. **Trade-offs.** Maintain own credential store (BCrypt, recovery) vs running/paying for an IdP. **Revisit when** SSO, a second client, or B2B tenants appear.

## ADR-015 — No RAG, vector store or LLM-written financial explanations
**Context.** No document corpus; users rely on exact figures; requirement for provenance and numerical verification. **Decision.** Defer all of it. If built later: LLM provides wording only; numbers are rendered from Java objects; an automated verifier rejects any output whose numerals differ from the provenance object. **Alternatives.** RAG over help docs; pgvector. **Trade-offs.** No conversational layer; no hallucination risk. **Revisit when** a specific evidence-grounded use case with a corpus exists.

## ADR-016 — Redis/Valkey deferred
**Context.** In-memory limiter and refresh-grace state are single-instance (O-02/O-10). **Decision.** Keep in-memory until ≥ 2 replicas; then try a PostgreSQL-backed limiter first; Valkey 9.x only if latency/throughput requires it. **Alternatives.** Redis now; sticky sessions. **Trade-offs.** None today; avoids a stateful dependency. **Revisit when** a second backend replica is deployed.

## ADR-017 — Supply-chain controls are baseline, not optional
**Context.** No scanning/SBOM/signing; one high advisory present **[V]**. **Decision.** Adopt Renovate, CodeQL, gitleaks, OSV/Trivy, Syft SBOM, cosign signing with provenance, SHA-pinned actions. **Alternatives.** Manual audits. **Trade-offs.** CI time (+3–5 min) and PR noise vs measurable risk reduction. **Revisit when** a tool's false-positive rate exceeds its value; swap tools, keep the controls.
