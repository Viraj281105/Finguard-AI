# Decisions

Two parts: decisions already taken and implemented (so nobody re-litigates them without a reason), and **open
decisions** that need the project owner's answer. Each open decision has a recommendation and the version it blocks.

## A. Taken

| # | Decision | Reason | Where |
|---|---|---|---|
| A-01 | **Modular monolith + one separate Python service.** No other services. | Small team; no measured scaling problem; one database transaction boundary for financial data. Extraction criteria are in [ARCHITECTURE.md](ARCHITECTURE.md#when-a-module-may-become-its-own-service). | ARCHITECTURE |
| A-02 | **Spring Boot owns all data**; the Python service is stateless and has no database access. | Single source of truth; reproducible results. | ARCHITECTURE |
| A-03 | **Financial numbers are computed deterministically in Java** (`BigDecimal`). Python is used only for classification and forecasting. A model's output never overrides arithmetic or a user's own category. | Reproducibility and testability. | FINANCIAL_RULES |
| A-04 | **Flyway owns the schema**; Hibernate only validates. Existing `ddl-auto=update` databases are adopted as baseline V1. | Controlled evolution, no silent drift. | DATABASE |
| A-05 | **Tests use embedded PostgreSQL 15**, not an in-memory substitute and not Docker. | Real SQL/constraints, works without a Docker daemon. | TESTING |
| A-06 | **Another user's record is `404`, never `403`.** | Does not reveal that an id exists. | API |
| A-07 | **Existing endpoint paths and the `token` storage key were kept** through the refactor. | Compatibility with existing clients and sessions. | API |
| A-08 | **Package layout is by business module** (`identity`, `ledger`, `ingestion`, `ai`, `common`) with `web`/`service`/`domain` inside. Rules enforced by ArchUnit. | Cohesion and testable boundaries. | ARCHITECTURE |
| A-09 | **Frontend is organised by feature** with a `shared/` layer; pages load through `useResource`. | Removes duplication; feature-local change. | ARCHITECTURE |
| A-10 | **Unknown/unparseable data is reported, not guessed** (principle; implemented for classification fallback, planned for import and analytics). | Trustworthy financial output. | FINANCIAL_RULES |
| A-11 | **Semantic versioning, pre-1.0 (`0.y.z`); one product version for all components; tags `vX.Y.Z`.** | See [VERSIONING.md](VERSIONING.md). | VERSIONING |
| A-12 | **Fixed, versioned category taxonomy of 19 ids** (`contracts/categories.json`): the requested personal-finance list plus `INSURANCE`, `CARD_PAYMENTS` and `REFUNDS`. Transfers, card payments, investments and refunds carry a non-expense *flow*. Resolves D-14. | Money movements must not be counted as spending; one list shared by Python, Java, SQL and TypeScript, each verified by a test. | FINANCIAL_RULES §5 |
| A-13 | **0.5.0 ships a deterministic rule classifier, not a trained model.** `Classifier` is a protocol and `ClassifierChain` lets a model complement the rules later. | No real labelled data exists (D-06); a model trained on text the author also wrote would produce an impressive, meaningless number. Adding scikit-learn would also reopen the Python 3.14 wheel question (KI-025) for no measurable gain. | ai/README, ai/evaluation |
| A-14 | **Persist first, classify second; user category wins by row lock.** Transactions are saved before the AI service is called, and automatic categories are applied only to rows still `NONE` (`SELECT … FOR UPDATE`). | Financial persistence must not depend on an optional service; a person's choice must win even in a race. | ARCHITECTURE §5 |
| A-15 | **Service-to-service shared secret (`X-Internal-Token`) is optional.** Enforced by the AI service when `AI_INTERNAL_TOKEN` is set; sent by the backend when configured. | Local development needs no setup; shared environments must set it (KI-033). | API |

## B. Open — need an answer

| # | Question | Options | Recommendation | Blocks |
|---|---|---|---|---|
| D-01 | **Where do session tokens live in the browser?** | (a) keep access token in `localStorage` (today); (b) access token in memory + refresh token in an `HttpOnly; SameSite=Strict` cookie | (b). It removes token theft by XSS; the backend already allows credentials in CORS. Needs a CSRF-safe refresh endpoint and a logout endpoint. | v0.2.0 |
| D-02 | **Access-token lifetime and password policy** | Today 24 h and min 6 chars. Original plan said 15 min access / 7 d refresh. | 15 min access with silent refresh, 7 d refresh, min 10 characters (and a blocklist check later) | v0.2.0 |
| D-03 | **Server-side refresh-token revocation?** | (a) stateless rotation only; (b) store hashed refresh-token ids so logout and reuse-detection work | (b) — it needs one table and makes "log out" real | v0.2.0 |
| D-04 | **Rate limiting approach** | In-process limiter keyed by IP+email (no new service) vs. adding a library (the old README mentioned Bucket4j) vs. reverse-proxy level | Small in-process limiter now; revisit at deployment. Valid only for a single instance. | v0.2.0 |
| D-05 | **Normalise emails to lower case?** | Migration lower-cases existing emails and adds a case-insensitive unique index; collisions need manual resolution | Yes | v0.2.0 |
| D-06 | **Real statements and labelled data:** can the owner supply anonymised statements from each bank and a labelled sample? | If not, parsers and classifier stay verified only on synthetic data, and no accuracy figure can be claimed | Supply at least 2–3 statements per bank (anonymised) | v0.4.0, v0.5.0 |
| D-07 | **API versioning:** the current `/api/*` has no version prefix | (a) treat as v1, introduce `/api/v2` only for a breaking change; (b) add `/api/v1` for new endpoints | (a). Internal AI contract is separately versioned (`/v1/…`). | v0.3.0 |
| D-08 | **Charts library** (none installed) | Recharts (original plan) · a light SVG/D3 helper · uPlot | Recharts: widely used, adequate for bars/lines/areas/donuts | v0.6.0 |
| D-09 | **What are "liquid assets" for the risk score's liquidity dimension?** No balances or accounts exist. | (a) latest statement closing balance + liquid investments (FD/MF types); (b) a user-entered cash balance; (c) both | (c): capture statement balances where available, allow manual override | v0.7.0 (affects v0.4.0 capture) |
| D-10 | **Transfers and refunds:** how aggressive is auto-detection? | Rule-based detection with user confirmation vs. detection only | Rule-based suggestion; the user can confirm or override per transaction; an override always wins | v0.6.0 |
| D-11 | **Deployment target** | VPS + Docker Compose + reverse proxy (original plan), PaaS, other | VPS + Compose, because the stack is already Compose-shaped. Not designed until v1.0.0. | v1.0.0 |
| D-12 | **Is a working Docker stack a release gate for v0.1.0?** | Tag 0.1.0 on the refactor alone, or only after Compose/`start.ps1` are verified | Gate it — the version is advertised as runnable | v0.1.0 |
| D-13 | **Forecast method:** numpy-only trend/seasonal baseline with empirical intervals vs. `statsmodels` ETS vs. Prophet | numpy-only baseline first (explainable, light); Prophet excluded (heavy native dependency) | v0.8.0 |
| D-14 | ~~**Classification taxonomy**~~ **Taken in 0.5.0** — see A-12 (19 ids, shared contract). | — | — | — |
