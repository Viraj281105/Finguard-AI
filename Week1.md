# FinGuard AI — Week 1 Progress

> **Historical document (March 2026).** It records what Week 1 delivered. The project has moved on: setup
> instructions below were updated to match the current repository, and a few statements that are no longer true are
> struck through or annotated. For current information see [README.md](README.md) and [docs/](docs/README.md).

## What Was Built
- Spring Boot 3.5.11 backend with JWT authentication
- PostgreSQL 15 via Docker
- React + Vite + TypeScript + Tailwind CSS frontend
- Login, Register, and Dashboard pages
- ~~End-to-end auth flow working~~ — *Claim as written in March; later found wrong: the registration page could not succeed (it read the response at the wrong level and stored the token under a different key). Fixed during the 2026 refactor and covered by a test; see [docs/CHANGELOG.md](docs/CHANGELOG.md).*

## API Endpoints
| Method | Endpoint | Description | Auth Required |
|--------|----------|-------------|---------------|
| POST | /api/auth/register | Register new user | No |
| POST | /api/auth/login | Login existing user | No |

*(Added later: `POST /api/auth/refresh`, and the transaction / investment / loan / upload endpoints — see [docs/API.md](docs/API.md).)*

## Request/Response Format

### Register
```json
POST /api/auth/register
{
  "name": "Viraj",
  "email": "viraj@example.com",
  "password": "password123"
}
```

### Response (both register and login)
```json
{
  "accessToken": "eyJ...",
  "refreshToken": "eyJ...",
  "email": "viraj@example.com",
  "name": "Viraj"
}
```

## Setup From Scratch (New Machine) — *updated for the current repository*

### Prerequisites
- Java 21
- Node.js (24 was used recently; 18 was the original note and has not been re-tested)
- Docker Desktop (to run PostgreSQL and the AI service)
- Maven (included via mvnw)
- Python ≥ 3.11 (for the AI service tests)

### Steps

**1. Clone the repo**
```bash
git clone https://github.com/Viraj281105/Finguard-AI.git
cd Finguard-AI
```

**2. Start everything**
```powershell
.\start.ps1
```
The script now works from any folder (the original contained a path from another machine). It starts PostgreSQL and
the AI service in Docker and opens windows for the backend and frontend. *Unverified on the current machine: Docker was not running.*

**Or manually:**
```powershell
# Terminal 1 - Database (host port 5433, was 5432)
docker compose up -d --wait postgres

# Terminal 2 - Backend
cd backend
.\mvnw spring-boot:run

# Terminal 3 - Frontend
cd frontend
npm install
npm run dev
```

**3. Open the app**
- Frontend: http://localhost:5173
- Backend API: http://localhost:8080

Full instructions: [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

## Known Issues / Notes
- Windows timezone bug: PostgreSQL 15 rejects `Asia/Calcutta`. Fixed via
  `-Duser.timezone=Asia/Kolkata` in `pom.xml` jvmArguments. *(Still in place.)*
- The backend does not run in Docker; only PostgreSQL (and now the AI service) do. Backend runs locally via Maven. *(Still true.)*
- If a PostgreSQL service is installed on the host it usually owns port 5432; that is why Compose now publishes 5433.

## Team
- Viraj — Backend (Spring Boot, JWT, PostgreSQL)
- Bhumi — Frontend (React, Vite, Tailwind)

## Week 2 Plan *(completed in March 2026 — CRUD APIs, dashboard shell and list pages, add forms; see [docs/CHANGELOG.md](docs/CHANGELOG.md))*
- [x] #6 JPA entities + CRUD APIs (Transaction, Investment, Loan)
- [x] #7 Dashboard shell + Transactions page
