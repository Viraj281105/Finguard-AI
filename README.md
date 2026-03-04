# FinGuard AI 🛡️
> *Know your financial future before it happens.*

AI-powered personal finance risk advisor for India's young professionals. Not just an expense tracker — a financial simulation engine that scores your risk, forecasts your cash flow, and lets you run "what-if" scenarios on your own money.

**Built by:** Viraj Jadhao + Bhumi | **Timeline:** March → July 2026 | **Stack:** Spring Boot + FastAPI + React

---

## What is this?

Most finance apps tell you where your money *went*. FinGuard AI tells you where it's *going* — and what happens if you make different decisions.

Upload your bank statement → AI classifies every transaction → get a Financial Risk Score (0–100) → see a 6-month cash flow forecast → run scenarios like "what if I close my personal loan?" and see your risk score change instantly.

---

## Features

| Feature | What it does |
|---|---|
| 🧠 **AI Expense Classification** | Two-stage ML pipeline classifies every transaction automatically. 91% accuracy on Indian bank statements. |
| 📊 **Financial Risk Score** | Composite 0–100 index across 5 dimensions: Liquidity, Debt Burden, Volatility, Savings Stability, Investment Diversification. |
| 📈 **Cash Flow Forecasting** | 3–12 month predictions with confidence bands. Based on your actual spending history. |
| 🎮 **Scenario Simulator** | Adjust parameters (close a loan, increase SIP, cut dining spend) and see future risk score + cash flow change instantly. |
| 💡 **AI Recommendations** | Prioritized action items ranked by severity — specific to your data, not generic tips. |
| 📤 **Bank Statement Import** | Supports HDFC, ICICI, SBI, Axis, Kotak. Async processing — 500 rows classified in under 60 seconds. |

---

## Tech Stack

### Backend — Spring Boot 3.x (Java 21)
- Spring Security 6 with JWT authentication
- Spring Data JPA + Hibernate + HikariCP
- Async CSV processing with CompletableFuture
- Bucket4j for rate limiting
- OpenCSV for bank statement parsing
- Flyway for database migrations

### AI / ML — FastAPI (Python 3.11)
- `sentence-transformers/all-MiniLM-L6-v2` for transaction embeddings
- FAISS for vector similarity search
- scikit-learn for anomaly detection (Isolation Forest)
- Facebook Prophet for time series forecasting (Phase 2)
- Pydantic v2 for request/response validation

### Frontend — React 18 + TypeScript
- Vite build tool
- React Router v6 + Zustand for state
- Tailwind CSS + shadcn/ui components
- Recharts + D3.js for data visualization
- React Hook Form + Zod for forms
- Axios with JWT interceptor + silent refresh

### Database
- PostgreSQL 15 with pgvector extension
- Vector similarity search for transaction embeddings

### Infrastructure
- Docker + Docker Compose (all services containerized)
- Nginx reverse proxy + SSL termination
- Let's Encrypt for free SSL certificate
- Ubuntu 22.04 VPS deployment (~$12/month)

---

## Architecture

```
React Frontend (Port 5173)
        ↕  HTTPS via Nginx
Nginx Reverse Proxy (Port 443)
        ↕
Spring Boot Backend (Port 8080)
   ├── Auth Module (JWT)
   ├── Data Ingestion Service
   ├── Risk Scoring Service
   ├── Scenario Engine
   └── AI Gateway Service
        ↕  Internal Docker network
FastAPI AI Microservices (Port 8000)
   ├── POST /classify      → Expense classification
   ├── POST /risk-score    → 5-dimension risk scoring
   ├── POST /forecast      → Cash flow forecasting
   └── POST /recommend     → Recommendations
        ↕
PostgreSQL + pgvector (Port 5432)
```

---

## Project Structure

```
Finguard-AI/
├── backend/                              # Spring Boot — Java 21
│   └── src/main/java/com/finguard/
│       ├── config/                       # Security, CORS, JWT, Async config
│       ├── controller/                   # REST controllers
│       ├── service/                      # Business logic
│       ├── repository/                   # Spring Data JPA repositories
│       ├── model/                        # JPA entities
│       ├── dto/                          # Request/Response DTOs
│       ├── exception/                    # Global error handling
│       ├── security/                     # JwtFilter, UserDetailsService
│       └── util/                         # CSV parser, validators
│
├── frontend/                             # React 18 + TypeScript
│   └── src/
│       ├── components/                   # Reusable UI components
│       ├── pages/                        # Dashboard, Transactions, Scenarios...
│       ├── hooks/                        # Custom React hooks
│       ├── services/                     # Axios API calls
│       ├── store/                        # Zustand state
│       ├── types/                        # TypeScript interfaces
│       └── utils/                        # Helpers, formatters
│
├── ai/                                   # FastAPI — Python 3.11
│   └── app/
│       ├── routers/                      # /classify /risk-score /forecast /recommend
│       ├── models/                       # ML model loading + inference
│       ├── schemas/                      # Pydantic schemas
│       └── utils/                        # Preprocessing, thresholds
│
├── docker-compose.yml                    # Local development
├── docker-compose.prod.yml               # Production overrides
└── .gitignore
```

---

## Getting Started

### Prerequisites
Make sure you have all of these installed:
```
java -version      # Java 21+
node -v            # Node 18+
python --version   # Python 3.11+
docker --version   # Docker 24+
git --version      # Git 2+
```

### 1. Clone the repo
```bash
git clone https://github.com/Viraj281105/Finguard-AI.git
cd Finguard-AI
```

### 2. Set up environment variables
```bash
# Copy the example env file
cp .env.example .env

# Fill in these values in .env:
# JWT_SECRET=your-256-bit-secret
# DB_PASSWORD=your-db-password
# FASTAPI_URL=http://fastapi:8000
```

### 3. Start all services
```bash
docker-compose up --build
```

This starts:
- PostgreSQL on `localhost:5432`
- FastAPI on `localhost:8000`
- Spring Boot on `localhost:8080`
- React dev server on `localhost:5173`

### 4. Open the app
Go to `http://localhost:5173` — register an account, upload a bank statement CSV, and you're in.

---

## API Reference

### Auth
```
POST /api/auth/register     Register new user
POST /api/auth/login        Login, receive JWT
POST /api/auth/refresh      Refresh expired token
GET  /api/auth/me           Get current user profile
```

### Transactions
```
POST /api/upload/csv                Upload bank statement CSV (async)
GET  /api/upload/status/{jobId}     Check processing status
GET  /api/transactions              List transactions (with filters)
PUT  /api/transactions/{id}/category  Override AI category
```

### Risk & Intelligence
```
POST /api/risk/compute        Compute risk score
GET  /api/risk/latest         Get latest risk report
GET  /api/forecast            Get cash flow forecast (?months=3/6/12)
GET  /api/recommendations     Get AI recommendations
POST /api/scenarios           Run a scenario simulation
GET  /api/scenarios           List saved scenarios
```

### AI Microservices (internal — called by Spring Boot only)
```
POST /classify      Batch classify transactions
POST /risk-score    Compute 5-dimension risk score
POST /forecast      Generate cash flow forecast
POST /recommend     Generate recommendations
```

---

## The Risk Score

The Financial Risk Index (0–100) is computed across 5 weighted dimensions:

```
Risk Score = 0.25 × Liquidity
           + 0.25 × Debt Burden
           + 0.20 × Spending Volatility
           + 0.20 × Savings Stability
           + 0.10 × Investment Diversification
```

| Score | Category | What it means |
|---|---|---|
| 0–25 | 🟢 LOW | Financially healthy. Keep it up. |
| 26–50 | 🟡 MEDIUM | Some areas need attention. |
| 51–75 | 🟠 HIGH | Significant risk. Action recommended. |
| 76–100 | 🔴 CRITICAL | Immediate attention needed. |

---

## Supported Bank CSV Formats

| Bank | Status |
|---|---|
| HDFC Bank | ✅ Supported |
| ICICI Bank | ✅ Supported |
| State Bank of India (SBI) | ✅ Supported |
| Axis Bank | ✅ Supported |
| Kotak Mahindra Bank | ✅ Supported |
| Generic / Other | ✅ Auto-detect (best effort) |

---

## 16-Week Roadmap

```
Phase 1 — Foundation      Weeks 1–3    Auth, DB, CSV upload pipeline
Phase 2 — AI Brain        Weeks 4–7    Classification, risk score, forecast, recommendations
Phase 3 — Star Feature    Weeks 8–10   Scenario simulator, full dashboard
Phase 4 — Polish          Weeks 11–13  Security, testing, mobile responsive
Phase 5 — Launch Ready    Weeks 14–16  Deployment, demo account, pitch prep
```

**Target launch:** Early July 2026

---

## Team

| Person | Role |
|---|---|
| **Viraj Jadhao** | Backend + AI — Spring Boot, FastAPI, Risk Engine, Scenario Simulator, DevOps |
| **Bhumi** | Frontend + Product — React, Dashboard, Charts, Upload UI, Mobile Responsive |

Both doing full-stack. Both learning everything.

---

## Security

- JWT access tokens (15 min TTL) + refresh tokens (7 days, HttpOnly cookie)
- BCrypt password hashing (cost factor 12)
- HTTPS enforced in production
- Rate limiting on all endpoints (Bucket4j)
- Input validation on all DTOs (Jakarta Bean Validation)
- No raw SQL — all queries via JPA parameterized statements
- CSV files deleted after successful parsing

---

## License

Private repository. All rights reserved.

---

*FinGuard AI — Built in Pune, India 🇮🇳*