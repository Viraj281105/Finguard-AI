# FinGuard AI — AI service

A small, **stateless** FastAPI service called by the Spring Boot backend. It never reads or writes the database and is
never the source of truth for financial data.

## Status

| Capability | Status |
|---|---|
| `GET /health` → `{"status":"ok","service":"finguard-ai","version":"0.5.0","capabilities":["classify"],"classification":{…}}` | ✅ implemented & tested |
| `POST /v1/classify` — transaction categories | ✅ implemented & tested. **Deterministic rule baseline (`rules-1.0.0`), not a machine-learning model.** |
| Trained ML model | 🗓️ not built. No real labelled data exists (decision D-06 / A-13); `ClassifierChain` is where a model would join. |
| `POST /v1/forecast` | 🗓️ planned, v0.8.0 |

`capabilities` lists exactly the endpoints that exist (a test compares it with the OpenAPI paths). Confidence returned
by the classifier is a rule-strength tier, **not a calibrated probability** (`confidence_calibrated: false`).

## How classification works

`app/classification/`:

| File | Role |
|---|---|
| `taxonomy.py` | The 19 category ids with their flow and allowed directions. Must equal `../contracts/categories.json` (tested). |
| `normalize.py` | Lowercases, folds accents, drops reference numbers / IFSC codes / UPI bank handles, collapses separators. |
| `rules.py` | The rule table: 582 token-boundary regexes, each with a `strength` (weak 0.55 / medium 0.75 / strong 0.90) and a `priority` (business rules 100 > specific 80 > merchant 60 > keyword 40 …). |
| `engine.py` | `RuleBasedClassifier`: collect matches, drop categories that do not fit the direction, let the strongest evidence win, **abstain** on ties, unknown narrations and direction conflicts. |
| `base.py` | `Classifier` protocol, `Classification`, `ClassifierChain` (rules first, a future model only for what rules decline). |

It abstains (`UNCATEGORIZED`, `method: "none"`, confidence 0, with a `reason`) for unknown merchants, UPI payments to
people, ATM cash, payment gateways, ambiguous narrations and credits that name an expense merchant. Same input → same output.
Descriptions are never logged. Contract: [docs/API.md](../docs/API.md#internal-contract-backend--ai-service).

## Evaluation

```powershell
.\.venv\Scripts\python -m evaluation.evaluate          # rewrite evaluation/report.md and metrics.json
.\.venv\Scripts\python -m evaluation.evaluate --check  # fail if metrics.json no longer matches the classifier
```
The data (`evaluation/data/`) is **synthetic and written by the same author as the rules**, so the figures are
optimistic. Read [`evaluation/report.md`](evaluation/report.md) before quoting any number: it contains the first-look
result, the rule changes made afterwards, and what it cannot tell you.

## Run

```powershell
python -m venv .venv
.\.venv\Scripts\python -m pip install -e ".[dev]"
.\.venv\Scripts\python -m pytest -q                                   # 121 tests
.\.venv\Scripts\python -m ruff check .
.\.venv\Scripts\python -m uvicorn app.main:app --port 8000            # http://localhost:8000/health
```
Docker: `docker compose up -d --wait ai` from the repository root (the image build is **unverified**; it uses Python 3.12,
while development used 3.14.7 — [docs/KNOWN_ISSUES.md](../docs/KNOWN_ISSUES.md) KI-018; the service needs no
scientific libraries, so the Python-version question in KI-025 is closed for now).

Environment: `AI_LOG_LEVEL` (default `INFO`); `AI_INTERNAL_TOKEN` — shared secret the backend sends as
`X-Internal-Token`; when empty (default) the check is **disabled** (local development only, KI-033).

## Layout

```
app/main.py            application factory
app/api/               health.py, classify.py
app/core/              config.py (environment), security.py (shared secret)
app/schemas/           classify.py (request/response models)
app/classification/    taxonomy, normalize, rules, engine, base
evaluation/            evaluate.py, export_examples.py, data/ (synthetic), report.md, metrics.json
tests/                 pytest (classifier, API, taxonomy, evaluation, health)
Dockerfile · pyproject.toml
```
`evaluation/` is not shipped in the Docker image. After changing rules run `python -m evaluation.evaluate` and
`python -m evaluation.export_examples` (refreshes `../contracts/classify-examples.json`, which the Java tests replay).

## Contract with the backend

Rules for this service ([docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md#5-python-ai-service)): stateless, versioned paths,
deterministic for a given input and model version, no logging of transaction descriptions. The Java client is tested
against recorded exchanges, a JDK HTTP server (timeouts, refused connections, header) and this service running as a
real process (`LiveAiServiceContractTest`, `LiveAiImportTest`).
