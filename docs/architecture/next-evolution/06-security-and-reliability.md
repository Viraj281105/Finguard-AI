# 06 — Security, Governance and Reliability

Labels: **[V]** verified 2026-10-28 · **[H]** historical claim · **[E]** estimate · **[A]** hypothesis · **[R]** recommendation. Genuine requirements are marked **REQ**; optional enterprise features are marked **OPT**.
*This document is engineering analysis, not legal advice.*

## 1. Threat model (STRIDE over the real trust boundaries)

Assets: (1) a user's transaction history (narrations contain merchants and people), (2) credentials and sessions, (3) the integrity of figures shown, (4) availability, (5) the AI service as a calculator that must not alter facts.

| Boundary | Threat | Existing control **[V unless H]** | Gap → work item |
|---|---|---|---|
| Browser ↔ API | Stolen session via XSS | Refresh token only in HttpOnly SameSite=Strict cookie **[H]** (tests green); CSP `default-src 'self'; frame-ancestors 'none'`, nosniff, frame-deny **[V]**; access token (15 min) still in `localStorage` | **REQ** HSTS (W-16), memory-only access token or accept documented trade-off (O-02) → S1-06/S3 |
| Browser ↔ API | CSRF | Bearer header for API; cookie only for `/refresh` with SameSite=Strict **[H]** | add an explicit CSRF test for `/api/auth/refresh` + `/logout` (S1-06) |
| API | Credential stuffing/brute force | Bounded LRU limiter (IP+email, IP, account), trusted-proxy aware **[V code, H behaviour]** | In-memory only → fails open across replicas (W-08) → shared limiter when ≥ 2 replicas |
| API | IDOR / cross-tenant | Repository calls scoped by user; IDOR tests all 404 **[H]** | **REQ** authorization matrix test generated from controllers + Postgres RLS as backstop (S1-06) |
| API | Malicious file upload (CSV) | 5 MB cap, row cap, strict dates, bounded errors, injected-formula risk irrelevant (never re-exported as spreadsheet) **[H]** | **REQ** CSV export (S1-05) must neutralise leading `= + - @` cells; parser fuzz test (S1-09) |
| API → AI service | Spoofed caller / data exfiltration | Optional shared secret **[V]** | **REQ** mandatory secret (S0-06); no outbound egress from AI; private network |
| API → DB | SQL injection | JPA/Spring Data, no string-built SQL found in samples **[A — not exhaustively audited]** | SAST (CodeQL) in CI (S0-07); grep rule for `createNativeQuery` with concatenation |
| Supply chain | Malicious/vulnerable dependency or action | None automated | **REQ** S0-07: lockfiles, pinned action SHAs, Renovate, audit gates, SBOM; S3-02 signing |
| Operator | Secret leakage | `.env` git-ignored; throwaway staging secrets; prod fail-closed guard **[V/H]** | secret scanning (gitleaks) in CI + pre-commit; secret manager in prod; rotation runbook |
| Telemetry/logs | PII leakage | Descriptions never logged in AI service **[H]**; row-error snippets are sanitised **[H]** | **REQ** redaction test: ingest a statement, assert no narration text appears in logs/spans (S1-01) |
| Airflow/batch | Pipeline gains write access to ledger | n/a | **REQ** read-only role; no route to API; separate metadata DB ([§6](#6-orchestration-isolation)) |

## 2. Authentication and authorization roadmap

| Item | Decision | Class | Work item |
|---|---|---|---|
| First-party email+password with BCrypt, rotating refresh family, row-locked redemption | **Keep** | REQ (exists) | — |
| OAuth 2.1 / OIDC provider (Keycloak, etc.) | **Reject now** — no second client, no SSO need; adds an HA stateful system | OPT | Revisit if B2B/SSO appears |
| Passkeys (WebAuthn) as primary or second factor | **Pilot** with Spring Security's built-in WebAuthn support (verify version support at adoption) | REQ for a finance product that stores real data (recommended before public launch) | S2-07 |
| TOTP MFA | Fallback if passkeys are deferred | OPT | S2-07 alt |
| Access-token revocation (O-02) | Short TTL (15 min) accepted; add deny-list when ≥ 2 replicas (shared store) | REQ at multi-replica | with Valkey/PG limiter decision |
| JWT signing key rotation | Single `JWT_SECRET` today **[V]**; add `kid` + key set (current + previous) and a rotation runbook; consider asymmetric (EdDSA/RS256) only if a second verifier appears | REQ | S1-06 |
| Roles/RBAC | None needed (single role: owner of own data). Operator endpoints (`/internal/*`) get a separate management port or deny at the edge | REQ for internal endpoints | S1-01 |

## 3. Secrets, keys, encryption

* **Secrets:** environment variables from a secret manager at runtime (managed platform feature); never in images, Git, logs or test reports. CI uses OIDC federation to the cloud (no static cloud keys). `scripts/staging-up.ps1` generating throwaway secrets into git-ignored `.staging/` is acceptable for local staging **[V]**.
* **Rotation:** `AI_INTERNAL_TOKEN` (dual-accept window), DB passwords (managed rotation), JWT key set, GitHub tokens; each has a runbook entry and a calendar reminder; rotation drilled at least once before launch.
* **Encryption:** TLS everywhere outside loopback; managed PostgreSQL and object storage encrypted at rest with provider-managed keys (customer-managed KMS keys = **OPT**); field-level encryption of narrations = **OPT/deferred** (would break SQL search/rules; weigh against threat model after launch).
* **Scanning:** gitleaks in CI and pre-commit; a CI check that fails if any `.env`/`staging.env`-shaped file is tracked.

## 4. Reliability objectives (proposals — each becomes real after a 2-week baseline on staging)

| SLO | SLI (how measured) | Objective | Source of the number |
|---|---|---|---|
| SLO-1 Availability | Share of authenticated API requests not answered 5xx (excluding 4xx), from OTel/Micrometer `http.server.requests` | **99.5 % / 30 days** (≈ 3.6 h budget) | **[E]** pragmatic for a single-engineer product; raise only with on-call coverage |
| SLO-2 Read latency | p95 of GET list/analytics/forecast at ≤ 10k tx/user | **≤ 500 ms** | **[H]** audit saw < 340 ms single-user; to be re-measured with k6 |
| SLO-3 Import completion | % of ≤ 5 MB statements reaching a terminal state within 60 s | **99 %** p95 ≤ 60 s | **[H]** 10,000 rows in 9.9 s |
| SLO-4 Data integrity | Daily reconciler run: unexplained delta | **0 (exact)**, 100 % of runs | design invariant |
| SLO-5 Event freshness | publication → consumer completion | **p99 ≤ 60 s** | **[E]** |
| SLO-6 AI degradation | Imports completing with `classificationStatus` ≠ `UNAVAILABLE` | tracked, **not an availability SLO** (the service is optional by design) | **[V]** design |

**Error-budget policy:** if SLO-1/2/3 burn > 50 % of the budget in a window, feature work stops and the next planned item becomes reliability work until the burn rate is < 1×. Alerts use **multi-window burn-rate** rules (e.g., 14.4× over 1 h + 5 min, 6× over 6 h + 30 min), page only on SLO-1/3/4 burn, ticket on the rest. Dashboards are provisioned as code. Cost attribution: tag every cloud resource `app=finguard,env=…,component=…`; monthly cost report in the ops review.

## 5. Operations: runbooks, incidents, capacity

* **Runbooks** (`docs/runbooks/`): start/stop, deploy, rollback, DB failover/restore, rotate each secret, AI service down, import queue saturated, outbox lag/dead letters, reconciler failure, certificate expiry. One page each, with the exact commands and the verification step.
* **Incident process (lightweight, single engineer):** severity rubric (S1 data loss/breach, S2 SLO breach, S3 degraded), timeline note template, blameless post-incident review within 5 working days, every S1/S2 yields a regression test or alert.
* **Capacity planning [E]:** model per-user storage (≈ 1,500 transactions/yr × ≈ 600 B incl. indexes ≈ 0.9 MB/yr) and request rates; load-test (k6) at 10×/100× the target user count on staging with synthetic data; record CPU/memory/connection-pool saturation; HikariCP default `maximumPoolSize=10` × replicas must stay below `max_connections`.
* **Disaster recovery [E]:** **RPO ≤ 5 min** via managed PITR + WAL archiving; **RTO ≤ 2 h**; backups copied to a second region/bucket; **restore rehearsal quarterly** with a recorded time and a reconciler run on the restored copy (S0-10, S3-05). A backup that has never been restored is not counted.
* **Graceful shutdown/probes:** Boot graceful shutdown (`server.shutdown=graceful`), liveness = process only, readiness = DB reachable (Actuator groups), startup probe for migrations; import jobs in flight are marked per the existing recovery listener **[V]** — S3 improves this with a durable job claim (`SKIP LOCKED`) so a restart resumes instead of failing (KI-007).

## 6. Orchestration isolation

Airflow (S2) is structurally prevented from harming the ledger:
1. **Separate metadata DB and credentials**; no ledger credentials in Airflow connections except `finguard_airflow` (SELECT-only on named views/tables).
2. **No network path** from the API to Airflow; Airflow has no write role on `public` and cannot call the API with a user token.
3. DAG outputs go to object storage / `analytics` build schema only; promotion to `analytics` is a separate consumer-owned step.
4. A failing DAG can at worst leave a stale report or a stale derived table (both rebuildable); the reconciler flags staleness.
5. CI check: deploy tests assert the `finguard_airflow` role fails an `INSERT/UPDATE/DELETE` on `public.*` (security regression test).

## 7. AI governance and financial-claim integrity

Principles (already partly implemented **[V]**): deterministic rules first, abstain over guess, review flag, `confidence_calibrated=false` until calibrated, user choice never overwritten, ledger persisted before classification.

Additional rules for any future capability:
1. **A model may suggest a category; it may never write a figure.** Money arithmetic stays in Java.
2. **Promotion gates** for a new classifier version (Project F): frozen real holdout; coverage-versus-accuracy curve; per-category precision floor; abstention rate within bounds; calibration error reported; adversarial suite passes; shadow-mode comparison on live traffic (log-only) for N days; reversible config switch.
3. **Drift monitoring (S2):** weekly `needs_review` rate, user correction rate per rule/model version, share of `UNCATEGORIZED`; alert on ±X σ change. These come from the `transaction.category_reviewed` event.
4. **Synthetic results are never marketing.** Every report/metrics file that uses synthetic data carries the title prefix "SYNTHETIC —" and a machine-readable `data_provenance: synthetic` flag; README/UI must not cite them as accuracy.
5. **LLM-assisted explanations (if ever built):** the LLM receives a structured *provenance object* (figures computed by Java, with source row counts and formulas); output is accepted only if an automated verifier finds every numeral in the text in the provenance object (after formatting normalisation); otherwise the template fallback is shown. Every explanation shows its evidence. **RAG: rejected** until there is a documents corpus and a use case; no vector store.
6. **Rollback:** model/rules version is stored per transaction (`category_model_version`, `category_rule` **[V — V3/V5]**), so a bad version can be found and re-classified (only rows still `RULE`/`MODEL`, never `USER`).

## 8. Privacy by design and Indian obligations (checklist, not legal advice)

| Topic | Status | Plan |
|---|---|---|
| Lawful basis/notice/consent | not implemented | consent record + privacy notice before onboarding anyone but the owner; DPDP duties phase in around **2027-05-13** per law-firm summaries (consent-manager provisions ≈ 2026-11-13) — **verify against the Gazette and with counsel** |
| Data minimisation | partially (AI gets 3 fields) | document each field's purpose; do not retain raw CSV (already in-memory only **[V]**) |
| Right to access/erasure/correction | **absent** (W-09) | S1-05: export (JSON/CSV), delete (ordered, tested), correction exists via CRUD |
| Breach notification | no process | incident runbook includes a notification step and contact list |
| Children's data | n/a | age gate at registration if launched publicly |
| Cross-border transfer | n/a locally | choose an Indian cloud region for production data (cost and latency impact in [10](10-cost-and-operational-model.md)) |
| Tenant isolation | app-level | RLS backstop, authorization matrix test |
| Audit trail | absent | append-only `audit_log` (actor, action, subject, time, trace id, outcome; no narrations) |
| Retention | partial | table in [05 §8](05-data-and-event-architecture.md#8-retention-deletion-and-privacy-in-the-data-layer) |
| Account Aggregator / RBI-regulated flows | out of scope | revisit only with a regulated partner |

## 9. Row-Level Security design (S1-06 pilot)

* Add `app.current_user_id` as a transaction-scoped setting set by the data layer at transaction start (`SET LOCAL`), via a Hibernate/Spring `TransactionSynchronization` or a connection-customising `DataSource` wrapper.
* Policies: `USING (user_id = current_setting('app.current_user_id', true))` on `transactions`, `loans`, `investments`, `upload_jobs`, `budgets`, `financial_goals`, `goal_contributions`, `saved_scenarios`; `FORCE ROW LEVEL SECURITY`; the app role is **not** `BYPASSRLS`; the migrator/reconciler roles are separate.
* Risks: pooled connections (must `RESET` on return — `SET LOCAL` is tx-scoped, which is why it is chosen), background threads (import worker must set the setting), query-plan effects (test with `EXPLAIN` at 10k rows), and admin/maintenance paths (separate role).
* **Exit criteria:** with RLS on, a deliberately broken repository method that omits the `user_id` predicate returns **zero foreign rows** in the test; full suite green; p95 latency regression ≤ 10 %. If criteria fail, the pilot is reverted by `ALTER TABLE … DISABLE ROW LEVEL SECURITY`.

## 10. Software supply-chain hardening (Project H detail)

| Control | Where | Gate |
|---|---|---|
| Lockfiles with hashes (`uv lock` or `pip-compile --generate-hashes`, `package-lock.json`, Maven dependency convergence + `maven-enforcer`) | all three stacks | CI fails if lock out of date |
| Actions pinned to commit SHA; permissions `contents: read` default; OIDC for deploy | `ci.yml` | review rule + `zizmor`/`actionlint` job |
| Dependency updates | Renovate/Dependabot (weekly, grouped) | auto-merge patch when green |
| Vulnerability scanning | `npm audit --audit-level=high` (fail), OSV-Scanner for Maven/PyPI, Trivy on images | fail on fixable high/critical |
| SAST | CodeQL (Java, TypeScript, Python) | PR annotations; high blocks |
| Secret scanning | gitleaks + GitHub secret scanning | fail |
| SBOM | Syft/CycloneDX per image and per release | artifact attached |
| Signing and provenance | cosign keyless + GitHub artifact attestations; verify at deploy | deploy refuses unsigned digests |
| Base images | digest-pinned; distroless/JRE-slim; non-root; read-only rootfs | image test |
| Threat model + abuse-case regression tests | this section + tests in [09](09-testing-and-acceptance-criteria.md) | CI |

Scanners and CI actions are themselves supply-chain assets: pin their versions and verify their provenance; review their release notes before bumping.
