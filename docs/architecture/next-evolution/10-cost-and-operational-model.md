# 10 — Cost and Operational Model

**Everything in this document is an estimate [E].** Prices were *not* quoted from any vendor on 2026-10-28; ranges are order-of-magnitude figures in US dollars per month for a managed-container-platform deployment in an Indian cloud region, assuming typical public list pricing patterns. **Validate with the provider's calculator before committing.** Assumptions are stated so they can be re-run.

## 1. Assumptions

| Assumption | Value |
|---|---|
| Active users per stage | A: 1–10 · B: ≤ 1,000 · C: ≤ 50,000 · D: ≥ 500,000 (monthly active) |
| Ledger growth | ≈ 1,500 transactions / user / year; ≈ 600 B per row including indexes (≈ 0.9 MB / user / year) |
| Request rate | ≈ 40 API requests / active user / day, peak ≈ 10× average |
| Import behaviour | ≈ 1 statement / user / month, ≤ 5 MB |
| Backend sizing | 1 vCPU / 2 GB per task handles ≈ 50–100 rps for this workload **[E — to be measured in S2-01/S3-03]** |
| AI service | 0.25–0.5 vCPU / 0.5–1 GB; rules classifier is CPU-light |
| Engineer cost | **Excluded** (the dominant real cost); operational burden is given in hours/week |
| Region | Indian region for production data (latency + data-residency posture); typically a few percent to ~15 % above the cheapest global region **[E]** |

## 2. Stage model

| | **A — Portfolio / staging** | **B — Beta (≤ 1k MAU)** | **C — Growth (≤ 50k MAU)** | **D — Scale (≥ 500k MAU)** |
|---|---|---|---|---|
| Compute | Local Docker; optional 1 small VM for staging | Managed containers: backend ×2 (0.5–1 vCPU), ai ×1, SPA on CDN/static | backend ×4–8, ai ×2–3, autoscaling on CPU + latency | backend ×20+, ai ×5+, dedicated batch capacity |
| Database | Local PG 18 | Managed PG 18, 1–2 vCPU / 4–8 GB, PITR, private | Managed PG HA 4–8 vCPU / 16–32 GB + 1 read replica | Larger HA instance, **partitioned** `transactions`, 2+ replicas, pooler |
| Cache / limiter | none (in-memory) | none (or PG table once ≥ 2 backend tasks) | Valkey small (shared limiter, deny-list) | Valkey cluster |
| Messaging | none (Postgres outbox) | none | outbox still adequate unless the gate fired | decide Kafka/Redpanda per [05 §7](05-data-and-event-architecture.md#7-the-streaming-platform-gate-decision-not-now-maybe-later-possibly-never) |
| Batch/orchestration | Airflow local profile | Airflow on 1 small VM *or* cron + CI | Airflow (small managed/self-hosted) | managed Airflow; consider warehouse (L3/L4) |
| Observability | local LGTM container | managed free/low tier **or** 1 small VM LGTM | managed Prometheus/Grafana/Loki/Tempo, 14–30 d retention | managed, sampling tuned, cost attribution per component |
| Object storage | none | backups + exports (< 50 GB) | < 1 TB incl. cross-region copy | multi-TB, lifecycle tiers |
| Estimated monthly infra | **$0 – $25** | **$80 – $200** | **$900 – $2,500** (+ $200 – $600 managed observability) | **$8,000 – $25,000+** |
| Ops burden (single owner) | 2–4 h/week | 4–8 h/week (on-call by one person) | **10–20 h/week → needs a second engineer** | a team with on-call rotation |
| SLO posture | none | SLO-1…5 measured, no paging at night | paging + error budgets | multi-window burn rates, game days |
| Biggest cost drivers | — | managed PG, logs/traces retention | PG HA + replicas, observability, egress | DB, data transfer, observability volume |
| Architecture changes triggered | — | none (this plan's Stage 3 target) | Valkey/PG limiter; summary table (L1); read replica | partitioning (L2); maybe broker; maybe K8s per gate |

## 3. Marginal cost and burden of each optional technology

| Technology | Added infra cost **[E]** | Added ops burden | Adopt only when |
|---|---|---|---|
| Spring Modulith outbox | ≈ $0 (one table) | ≈ 0.5 h/week (purge/lag alert) | Stage 1 (already planned) |
| Valkey (small managed) | $15 – $60 | 1 h/week | ≥ 2 backend replicas and a measured need beyond a PG limiter |
| Airflow (small) | $20 – $60 (VM) / managed $300+ | 2–4 h/week | ≥ 4 DAGs with dependencies |
| Kafka/Redpanda | self-hosted 3 nodes ≈ $200 – $400; managed ≈ $300 – $800 minimum | 4–8 h/week | gate: ≥ 2 conditions measured |
| Kubernetes (managed control plane + 3 small nodes) | $150 – $400 before workloads | 4–8 h/week | gate: ≥ 3 of 5 conditions |
| Argo CD / Flux | ≈ $0 (runs in cluster) | 1–2 h/week | only with a real cluster |
| ClickHouse | $200+ (managed small) / self-hosted VM | 3–6 h/week | L4 trigger |
| OpenSearch | $150+ | 4 h/week | SIEM requirement |
| Self-hosted LGTM vs managed | VM $40 – $120 vs usage-based | 2–4 h/week vs ≈ 0 | choose by cost at the stage; record in ADR-012 |
| Go reconciler / Go worker | ≈ $0 (batch tiny) / a small service | 0.5 h/week / 1–2 h/week | per roadmap gates |

## 4. Cost controls built into the plan

1. **Tagging** (`app`, `env`, `component`) enforced by IaC (S3-01); monthly cost review in the ops cadence.
2. **Budget alarms** at 50/80/100 % of a monthly budget per environment.
3. **Scale-to-zero or off-hours shutdown for staging**; ephemeral preview environments only with a destroy-on-merge policy.
4. **Retention budgets**: traces 14 d, logs 14 d, metrics 30 d (raise only with a stated need).
5. **Sampling**: 10 % traces by default, 100 % for errors.
6. **No always-on pilots**: Kubernetes pilot on `kind` (free); Airflow and Kafka only in compose profiles until their gates fire.
7. **Right-size from data**: S2-01/S3-03 produce the per-task rps and memory numbers that replace the guesses above.

## 5. Operational burden model

| Activity | Frequency | Time **[E]** |
|---|---|---|
| Review Renovate PRs | weekly | 1 h |
| Triage alerts/dashboards | daily at B, continuous at C | 15 min / day |
| Quarterly support-window review | quarterly | 2 h |
| Restore drill | quarterly | 3 h |
| Secret rotation | quarterly | 1 h per secret family |
| Incident response + review | as needed | 2–8 h each |
| Cost review | monthly | 1 h |
| Performance re-baseline | per release with perf-sensitive changes | 2 h |

**Sustainability rule:** if recurring toil exceeds 40 % of available time, the next roadmap item becomes toil reduction or removal of a pilot component.

## 6. Cost of the plan itself

Engineering effort ≈ 156 days base / ≈ 180 with conditional items ([08](08-implementation-roadmap.md)). Infrastructure during Stages 0–2 is ≈ $0–25/month (local + GitHub free tiers); Stage 3 introduces the first real spend (Stage B figures above) once a managed target is chosen (decision D-11 in `docs/DECISIONS.md`).
