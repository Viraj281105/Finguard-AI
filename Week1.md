# FinGuard AI — Week 1 Progress

## What Was Built
- Spring Boot 3.5.11 backend with JWT authentication
- PostgreSQL 15 via Docker
- React + Vite + TypeScript + Tailwind CSS frontend
- Login, Register, and Dashboard pages
- End-to-end auth flow working

## API Endpoints
| Method | Endpoint | Description | Auth Required |
|--------|----------|-------------|---------------|
| POST | /api/auth/register | Register new user | No |
| POST | /api/auth/login | Login existing user | No |

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

## Setup From Scratch (New Machine)

### Prerequisites
- Java 21
- Node.js 18+
- Docker Desktop
- Maven (included via mvnw)

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

That's it! The script opens 3 terminals for Docker, backend, and frontend.

**Or manually:**
```powershell
# Terminal 1 - Database
docker-compose up

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

## Known Issues / Notes
- Windows timezone bug: PostgreSQL 15 rejects `Asia/Calcutta`. Fixed via
  `-Duser.timezone=Asia/Kolkata` in `pom.xml` jvmArguments.
- Docker backend service removed from `docker-compose.yml` — backend runs
  locally via Maven, only PostgreSQL runs in Docker.

## Team
- Viraj — Backend (Spring Boot, JWT, PostgreSQL)
- Bhumi — Frontend (React, Vite, Tailwind)

## Week 2 Plan
- [ ] #6 JPA entities + CRUD APIs (Transaction, Investment, Loan)
- [ ] #7 Dashboard shell + Transactions page
- [ ] #8 Add Transaction / Loan / Investment forms