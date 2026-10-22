# FinGuard AI 🛡️

> Personal financial intelligence for Indian users — **work in progress**.

FinGuard AI is being built to help someone understand how their financial decisions might affect their future:
import a bank statement, see where the money goes, get an explainable risk indicator, a cash-flow forecast, simulate
decisions ("what if I close this loan?") and receive recommendations based on their own data.

**Today** it is a working foundation: accounts, per-user transactions / investments / loans, and an asynchronous bank-statement
import for five Indian banks. Imported transactions are categorised by a **rule-based classifier** (not a trained model) and the user can review
and correct every category. **The rest of the intelligence layer — analytics, risk score, forecast, scenarios,
recommendations — is not built yet.** The roadmap is in [docs/ROADMAP.md](docs/ROADMAP.md).

Status labels used below: ✅ implemented & tested · ⚠️ implemented but limited/unverified · 🗓️ planned (does not exist).
Details and evidence: [docs/PROGRESS.md](docs/PROGRESS.md).

## What works today

| Capability | Status | Notes |
|---|---|---|
| Register, log in, refresh token | ✅ | JWT; 24 h access / 7 d refresh. No logout/revocation, no rate limiting yet |
| Transactions, investments, loans — create / list / edit / delete | ✅ | Strictly per user; another user's record is "not found" |
| Dashboard with totals and recent transactions | ⚠️ | Totals are calculated in the browser, all-time, using floating point |
| Bank-statement CSV import (HDFC, ICICI, SBI, Axis, Kotak) | ⚠️ | Async, atomic, with live status. Verified on **synthetic** files only; reads columns by position; unreadable rows are skipped silently; **importing a file twice duplicates it**; the bank's date is not stored |
| Automatic transaction categories | 🗓️ | The backend calls an AI endpoint that does not exist yet, so imported rows have no category |
| Financial metrics by month/category, recurring payments | 🗓️ | v0.6.0 |
| Explainable risk score | 🗓️ | v0.7.0 |
| Cash-flow forecast | 🗓️ | v0.8.0 |
| Scenario simulator | 🗓️ | v0.9.0 |
| Recommendations | 🗓️ | v0.10.0 |
| CI, containerised full stack, production deployment | 🗓️ | v0.2.0 (CI), v0.11.0, v1.0.0 |

No accuracy, performance or security-certification claims are made anywhere in this repository.

## Architecture in brief

```
React 19 + TypeScript + Vite + Tailwind  (port 5173)
        │  JSON + Bearer JWT
Spring Boot 3.5 · Java 21  (port 8080)  — modular monolith
   identity · ledger · ingestion · ai-client · common
        │ JPA / Flyway                         │ HTTP (planned endpoint)
PostgreSQL 15  (host port 5433)         FastAPI service (port 8000) — /health only
```
Spring Boot owns all data. The Python service is stateless and, for now, only reports its health. See
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Quick start (Windows / PowerShell)

Prerequisites: JDK 21, Node.js (24 used), Python ≥ 3.11, Docker Desktop (only to *run* the app; tests need no Docker).

```powershell
Copy-Item .env.example .env                 # optional: defaults work for local development
cd frontend; npm install; cd ..
.\start.ps1                                 # PostgreSQL + AI in Docker, backend and frontend in their own windows
```
Open http://localhost:5173 and register. `start.ps1` and the Docker parts are **unverified** on the development machine
(Docker was not running); the backend, frontend and AI service each start and pass their tests on their own.
Manual commands, configuration and troubleshooting: [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

## Run the tests

```powershell
.\scripts\test-all.ps1       # backend (70) + frontend typecheck/lint/tests (49) + AI (3); no Docker needed
```
Last observed result (2026-10-22): all passing. What is and is not covered: [docs/TESTING.md](docs/TESTING.md).

## API at a glance

| Area | Endpoints |
|---|---|
| Auth (public) | `POST /api/auth/register` · `/login` · `/refresh` |
| Transactions | `GET/POST /api/transactions` · `GET/PUT/DELETE /api/transactions/{id}` |
| Investments | `GET/POST /api/investments` · `GET/PUT/DELETE /api/investments/{id}` |
| Loans | `GET/POST /api/loans` · `GET/PUT/DELETE /api/loans/{id}` |
| Statement import | `POST /api/upload/csv` (multipart, `202`) · `GET /api/upload/status/{jobId}` |

Full contracts, error format and limits: [docs/API.md](docs/API.md). Endpoints that older documentation mentioned
(`/api/auth/me`, `/api/risk/*`, `/api/forecast`, `/api/scenarios`, `/api/recommendations`) **do not exist**.

## Known limitations

The most important ones (full list with versions: [docs/KNOWN_ISSUES.md](docs/KNOWN_ISSUES.md)):
parsers unverified on real statements · the classifier is rules only, evaluated on synthetic data (KI-030) ·
transfers/refunds are labelled but not yet used by analytics (v0.6.0) · basic session handling, no rate limiting (v0.2.0) · Docker
stack unverified · nothing committed from the refactoring (see PROGRESS).

## Repository layout

```
backend/    Spring Boot  (com.finguard.backend.{common,identity,ledger,ingestion,ai}; db/migration)
frontend/   React app    (src/{app,shared,features,test})
ai/         Python service (app/, tests/, Dockerfile)
docs/       Documentation — start at docs/README.md
scripts/    test-all.ps1
docker-compose.yml · .env.example · start.ps1
Week1.md · README.html     historical documents (see their notices)
```

## Documentation

[Index](docs/README.md) · [Roadmap](docs/ROADMAP.md) · [Release plan](docs/RELEASE_PLAN.md) · [Progress](docs/PROGRESS.md) ·
[Architecture](docs/ARCHITECTURE.md) · [API](docs/API.md) · [Database](docs/DATABASE.md) · [Financial rules](docs/FINANCIAL_RULES.md) ·
[Development](docs/DEVELOPMENT.md) · [Testing](docs/TESTING.md) · [Known issues](docs/KNOWN_ISSUES.md) · [Decisions](docs/DECISIONS.md) ·
[Versioning](docs/VERSIONING.md) · [Changelog](docs/CHANGELOG.md)

## Team and licence

Viraj Jadhao and Bhumi. Private repository; all rights reserved.
