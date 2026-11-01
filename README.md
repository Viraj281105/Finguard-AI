# FinGuard AI 🛡️

> Personal financial intelligence for Indian users — **work in progress** (version 0.10.0, untagged).

FinGuard AI helps someone understand how their financial decisions might affect their future: import a bank statement, see
where the money goes, review and correct categories, see a cash-flow forecast, simulate decisions ("what if I close this
loan?") and get recommendations grounded in their own data. Money figures are computed deterministically in the Java backend
with exact decimals; the Python service only suggests categories and can abstain.

Status labels used below: ✅ implemented & tested · ⚠️ implemented but limited/unverified · 🗓️ planned (does not exist).
Evidence and exact test counts: [docs/PROGRESS.md](docs/PROGRESS.md). Roadmap: [docs/ROADMAP.md](docs/ROADMAP.md).

## What works today

| Capability | Status | Notes |
|---|---|---|
| Register, log in, refresh, log out | ✅ | 15-minute access JWT, 7-day rotating refresh token in an HttpOnly cookie with reuse detection; login/refresh rate limiting (in-process, single instance); passwords 10–128 characters |
| Transactions, investments, loans — create / list / edit / delete | ✅ | Strictly per user; another user's record is "not found"; transaction list is paginated (default 20 per page) |
| Bank-statement CSV import (HDFC, ICICI, SBI, Axis, Kotak) | ⚠️ | Async, atomic, de-duplicated by fingerprint, per-row error report, reconciliation of row counts. Verified on **synthetic** files only — never on real bank exports |
| Automatic transaction categories | ⚠️ | Rule-based classifier (`rules-1.0.0`, no trained model) that abstains when unsure; people can review and correct every category and their choice is never overwritten. Quality on real statements is **unknown** |
| Analytics: monthly flows, category breakdown, recurring payments, unusual transactions, data-quality diagnostics, insights | ✅ | Server-side, exact decimals; transfers, card payments and investments excluded from income/expense; refunds netted |
| Budgets and savings goals | ✅ | Monthly overall/category limits, performance vs budget, goal contributions (self-tracked, not bank-verified) |
| Cash-flow, category, budget-outlook and savings forecasts; loan / one-time what-if scenarios; saved scenarios | ✅ | Statistical (no machine learning), with evidence rules and a per-user backtest; quality on real histories unknown |
| Recommendations | ✅ | General-guidance rules over your own figures, configurable thresholds; not financial advice |
| Explainable risk score | 🗓️ | Not built |
| Backend/frontend container images, production deployment | 🗓️ | Only PostgreSQL and the AI service are containerised |

No accuracy, performance or security-certification claims are made anywhere in this repository. Evaluation data for the
classifier is synthetic and written by the same author as the rules, so its scores say nothing about real-world accuracy.

## Architecture in brief

```
React 19 + TypeScript + Vite + Tailwind  (port 5173)
        │  JSON + Bearer JWT (refresh token in an HttpOnly cookie)
Spring Boot 3.5 · Java 21  (port 8080)  — modular monolith
   identity · ledger · ingestion · categorization · intelligence · budgeting · forecasting · ai-client · common
        │ JPA / Flyway                         │ POST /v1/classify (shared secret header)
PostgreSQL 15  (host port 5433)         FastAPI service (port 8000) — stateless, rules-based classifier
```
Spring Boot owns all data. The Python service has no database and is optional at runtime (imports complete without it).
See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Quick start (Windows / PowerShell)

Prerequisites: JDK 21, Node.js 24, Python ≥ 3.11, Docker Desktop (only to *run* the app; tests need no Docker).

```powershell
Copy-Item .env.example .env                 # optional: defaults work for local development
cd frontend; npm install; cd ..
.\start.ps1                                 # PostgreSQL + AI in Docker, backend and frontend in their own windows
```
Open http://localhost:5173 and register. Manual commands, configuration and troubleshooting:
[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md). A separate, isolated local staging stack (synthetic data only) is started with
`.\scripts\staging-up.ps1`.

## Run the tests

```powershell
.\scripts\test-all.ps1       # backend + frontend typecheck/lint/tests/build + AI lint/tests/evaluation check; no Docker needed
```
Last verified result (2026-10-28): 665 automated tests pass — 387 backend, 150 frontend, 128 AI — and the GitHub Actions
workflow runs the same three suites. What is and is not covered: [docs/TESTING.md](docs/TESTING.md).

## API at a glance

| Area | Endpoints |
|---|---|
| Auth | `POST /api/auth/register` · `/login` · `/refresh` · `/logout` |
| Transactions | `GET/POST /api/transactions` · `GET/PUT/DELETE /api/transactions/{id}` · `PATCH /api/transactions/{id}/category` · `POST /api/transactions/reclassify` |
| Investments, loans | `GET/POST /api/investments` · `/api/loans` and `GET/PUT/DELETE …/{id}` |
| Statement import | `POST /api/upload/csv` (multipart, `202`) · `GET /api/upload/status/{jobId}` · `GET /api/upload/jobs` |
| Analytics | `GET /api/analytics/{summary,monthly,categories,recurring,data-quality,insights}` |
| Budgets, goals | `/api/budgets` (+ `/overview`) · `/api/goals` (+ contributions) |
| Forecast, scenarios | `GET /api/forecast/{cash-flow,categories,budget-overruns,savings,backtest}` · `POST /api/forecast/scenarios/simulate` · `/api/scenarios` |
| Recommendations | `GET /api/recommendations` |

Full contracts, error format and limits: [docs/API.md](docs/API.md). Endpoints from older plans that **do not exist**:
`/api/auth/me`, `/api/risk/*`.

## Known limitations

The most important ones (full list with versions: [docs/KNOWN_ISSUES.md](docs/KNOWN_ISSUES.md)): parsers and the classifier have
never seen real bank data (KI-019, KI-030) · forecast quality on real histories is unknown (KI-040) · rate limiting and token
state are single-instance (O-10) · no backend/frontend images and no production deployment · no account deletion or data export.

## Repository layout

```
backend/    Spring Boot  (com.finguard.backend.{common,identity,ledger,ingestion,categorization,intelligence,budgeting,forecasting,ai}; db/migration)
frontend/   React app    (src/{app,shared,features,test})
ai/         Python service (app/, tests/, evaluation/, Dockerfile)
contracts/  Shared Java ↔ Python contract data (taxonomy, classification examples)
docs/       Documentation — start at docs/README.md
scripts/    test-all.ps1, staging-up.ps1, staging-down.ps1, check-docs.py
docker-compose.yml · docker-compose.staging.yml · .env.example · start.ps1
Week1.md · README.html     historical documents (see their notices)
```

## Documentation

[Index](docs/README.md) · [Roadmap](docs/ROADMAP.md) · [Release plan](docs/RELEASE_PLAN.md) · [Progress](docs/PROGRESS.md) ·
[Architecture](docs/ARCHITECTURE.md) · [API](docs/API.md) · [Database](docs/DATABASE.md) · [Financial rules](docs/FINANCIAL_RULES.md) ·
[Development](docs/DEVELOPMENT.md) · [Testing](docs/TESTING.md) · [Known issues](docs/KNOWN_ISSUES.md) · [Decisions](docs/DECISIONS.md) ·
[Versioning](docs/VERSIONING.md) · [Changelog](docs/CHANGELOG.md)

## Team and licence

Viraj Jadhao and Bhumi. All rights reserved; no open-source licence file is present in the repository.
