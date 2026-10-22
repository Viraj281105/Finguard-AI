# Development guide

Commands below were written from the repository's actual scripts and configuration. Anything that was **not run**
on the development machine is marked *unverified*. The machine used so far runs Windows 11 with PowerShell and Git Bash.

## 1. Prerequisites

| Tool | Needed for | Version used so far |
|---|---|---|
| JDK | backend | 21 (21.0.9). The Maven wrapper (`mvnw`) downloads Maven itself. |
| Node.js + npm | frontend | Node 24.19.0, npm 11.17. The minimum supported version has not been established. |
| Python | AI service | `>= 3.11` required by `pyproject.toml`; 3.14.7 used locally; the Docker image uses 3.12 |
| Docker Desktop | PostgreSQL + AI container for running the app | 29.7.2 installed, but the **daemon was not running** — everything Docker-based is *unverified* |
| Git | | |

**Running the automated tests does not need Docker or a local PostgreSQL**: backend integration tests download and
start an embedded PostgreSQL 15 (network access needed the first time; binaries are cached in the Maven repository
and extracted under `%TEMP%\embedded-pg`).

## 2. First-time setup

```powershell
# 1. Configuration (optional for local development — defaults work; see section 4)
Copy-Item .env.example .env

# 2. Frontend dependencies
cd frontend; npm install; cd ..

# 3. AI service virtual environment
cd ai
python -m venv .venv
.\.venv\Scripts\python -m pip install -e ".[dev]"
cd ..
```
The backend needs no setup step; `mvnw` fetches dependencies on first use.

## 3. Running the application

### One command (Windows) — *unverified*
```powershell
.\start.ps1          # add -NoAi to skip the AI container
```
It loads `.env` (if present), runs `docker compose up -d --wait postgres ai`, then opens one window for the backend
(`mvnw spring-boot:run`) and one for the frontend (`npm run dev`).

### Manually
```powershell
docker compose up -d --wait postgres      # database on host port 5433          (unverified)
cd backend;  .\mvnw spring-boot:run       # http://localhost:8080  (dev profile) (unverified against Compose DB)
cd frontend; npm run dev                  # http://localhost:5173
# optional: AI service without Docker
cd ai; .\.venv\Scripts\python -m uvicorn app.main:app --port 8000   # verified: /health answers
```

| URL | What |
|---|---|
| http://localhost:5173 | Web app (register at `/register`) |
| http://localhost:8080/api/… | API ([API.md](API.md)) |
| http://localhost:8000/health | AI service health |

The dev profile itself is verified to boot (`ProfilesTest`), against an embedded PostgreSQL. What has not been
verified is the combination "dev profile + Docker Compose database".

## 4. Configuration

Spring Boot reads **environment variables**; it does not read `.env`. Docker Compose reads `.env` automatically and
`start.ps1` exports it to the processes it starts. The full commented list is [`.env.example`](../.env.example).

| Variable | Used by | Default (dev) | Notes |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | backend | `dev` | Set `prod` anywhere that is not a developer's machine |
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | backend | `jdbc:postgresql://localhost:5433/finguard`, `finguard_user`, `finguard_pass` | Required with `prod` |
| `JWT_SECRET` | backend | a built-in dev-only key (dev profile) | **Required with `prod`**, at least 32 bytes, otherwise startup fails |
| `JWT_EXPIRATION_MS` / `JWT_REFRESH_EXPIRATION_MS` | backend | 86400000 (24 h) / 604800000 (7 d) | |
| `CORS_ALLOWED_ORIGINS` | backend | `http://localhost:5173` | comma-separated |
| `AI_SERVICE_URL` | backend | `http://localhost:8000` | Compose network name would be `http://ai:8000` |
| `POSTGRES_DB` / `_USER` / `_PASSWORD` / `_HOST_PORT` | Compose | `finguard` / `finguard_user` / `finguard_pass` / `5433` | |
| `AI_HOST_PORT`, `AI_LOG_LEVEL` | Compose / AI service | `8000`, `INFO` | |
| `VITE_API_BASE_URL` | frontend | `http://localhost:8080/api` | put in `frontend/.env.local`; see [`frontend/.env.example`](../frontend/.env.example) |

**Never commit `.env`, real credentials or a real `JWT_SECRET`.** `.gitignore` already excludes `.env`, `.env.local`,
`.env.production` and `*.secret`.

Upgrading from the old setup: the database used to be published on host port **5432**; it is now **5433**. An existing
Docker volume (`postgres_data`) and credentials are unchanged. An existing database created by the old
`ddl-auto=update` configuration is adopted automatically by Flyway ([DATABASE.md](DATABASE.md)). Existing browser
sessions stop working (token format changed) — log in again.

## 5. Commands

| Task | Command (from repo root) | Verified |
|---|---|---|
| Everything | `.\scripts\test-all.ps1` | yes — all green on 2026-10-22 |
| Backend tests | `cd backend; .\mvnw -B test` | yes (70 tests) |
| Backend compile only | `cd backend; .\mvnw -B -DskipTests compile` | yes |
| Frontend type check | `cd frontend; npm run typecheck` | yes |
| Frontend lint | `cd frontend; npm run lint` | yes |
| Frontend tests | `cd frontend; npm test` (watch mode: `npm run test:watch`) | yes (49 tests) |
| Frontend production build | `cd frontend; npm run build` | yes |
| AI tests | `cd ai; .\.venv\Scripts\python -m pytest -q` | yes (3 tests) |
| AI lint | `cd ai; .\.venv\Scripts\python -m ruff check .` | yes |
| Validate Compose file | `docker compose config` | yes (syntax only; needs no daemon) |
| Start infra | `docker compose up -d --wait postgres ai` | no — Docker daemon unavailable |

Git Bash equivalents: replace `.\mvnw` with `./mvnw` and `.\.venv\Scripts\python` with `./.venv/Scripts/python`.

## 6. Repository layout

```
backend/    Spring Boot (src/main/java/com/finguard/backend/{common,identity,ledger,ingestion,ai}, db/migration)
frontend/   React app (src/{app,shared,features,test})
ai/         Python service (app/, tests/, Dockerfile, pyproject.toml)
docs/       this documentation
scripts/    test-all.ps1
docker-compose.yml   PostgreSQL + AI service
.env.example         documented configuration
start.ps1            local stack launcher
Week1.md, README.html   historical documents (see their banners)
```
A few empty legacy folders and 28 tracked `.gitkeep` placeholder files remain from the original scaffold (KI-021).

## 7. Workflow

The existing history uses feature branches merged by pull request into `main`, and `feat:`-style commit subjects.
Continue that, tightened:

1. **Branch** from up-to-date `main`: `feature/<short-topic>` (or `fix/…`, `docs/…`, `chore/…`).
2. **Implement in small steps**; run the relevant suite often (`.\scripts\test-all.ps1` before pushing).
3. **Write tests with the change.** A bug fix starts with a failing test. A security or ownership rule gets a test that
   was seen to fail without the rule.
4. **Update documentation** in the same PR ([README.md](README.md#maintenance-rules)), including
   [CHANGELOG.md](CHANGELOG.md) under `[Unreleased]`.
5. **Commit** with Conventional Commits: `feat(ledger): …`, `fix(ingestion): …`, `docs: …`, `test: …`, `chore: …`,
   `refactor: …`; append `!` or a `BREAKING CHANGE:` footer for incompatible changes ([VERSIONING.md](VERSIONING.md)).
6. **Open a PR to `main`**; merge when the checks pass. There is no CI yet (KI-017), so the author runs
   `test-all.ps1` and records the result in the PR description.
7. Never edit a merged Flyway migration; never skip or weaken a test to make a build pass.

### Definition of done for a change
- [ ] Behaviour implemented in all affected layers (a UI control without a working endpoint is not done).
- [ ] Ownership/auth considered: user id from the security context only.
- [ ] Tests added; all suites green.
- [ ] Docs and changelog updated; no planned feature described as existing.
- [ ] Loading, empty, error and success states handled in any new UI.

## 8. Troubleshooting (seen on this project)

| Symptom | Cause / fix |
|---|---|
| `password authentication failed for user "finguard_user"` when starting the backend or tests | The app reached a *different* PostgreSQL (a local Windows service on 5432). The dev profile now targets port 5433; start the Compose database, or set `SPRING_DATASOURCE_URL`. |
| `failed to connect to the docker API … dockerDesktopLinuxEngine` | Docker Desktop is not running. Start it, or skip Docker: tests do not need it. |
| `jwt.secret (env JWT_SECRET) must be set and at least 32 bytes long` | `prod` profile without a secret. Intended. |
| `Schema-validation: missing column …` at startup | Entity and migrations disagree — add the migration ([DATABASE.md](DATABASE.md)). |
| Stale classes after moving packages (`ConflictingBeanDefinitionException`) | `.\mvnw clean` (deletes only the git-ignored `target/`). |
| Mockito "self-attaching" warning in test output | Known, harmless for now (KI-024). |
