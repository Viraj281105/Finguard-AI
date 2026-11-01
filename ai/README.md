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

## Evaluation & Governance (`S1-09`)

```powershell
.\.venv\Scripts\python -m evaluation.evaluate          # rewrite evaluation/report.md and metrics.json
.\.venv\Scripts\python -m evaluation.evaluate --check  # enforce manifest hashes, overlap, PII, provenance & regressions
```
The evaluation harness (`evaluation/evaluate.py`) enforces strict evaluation governance:
* **Dataset manifest (`evaluation/data/manifest.json`):** Cryptographic SHA-256 hashes (canonical LF-normalized), exact row counts, label procedures, and `data_provenance: "synthetic"` for `dev`, frozen `holdout`, and `adversarial` suites.
* **Zero train/holdout leakage:** Rejects any exact or normalized `(description, type)` overlap between `synthetic_dev.csv` and `synthetic_holdout.csv`.
* **Adversarial & perturbation suite (`synthetic_adversarial.csv`):** 28 cases across 7 perturbation categories (`homoglyphs`, `truncation`, `mixed_case`, `reference_noise`, `merchant_lookalikes`, `direction_mismatch`, `prompt_injection`).
* **Confidence-tier coverage vs. accuracy curve:** Evaluates precision, coverage, and wrong assertions at thresholds `>= 0.90`, `>= 0.75`, and `>= 0.55`.
* **Provenance stamp (`synthetic != real`):** Every generated report (`evaluation/report.md`) and metrics artifact (`evaluation/metrics.json`) carries an explicit `# SYNTHETIC —` provenance banner and `synthetic != real` (`synthetic ≠ real`) disclosure. No real bank statement data may be committed to git.

The data (`evaluation/data/`) is **synthetic and written by the same author as the rules**, so the figures are
optimistic. Read [`evaluation/report.md`](evaluation/report.md) before quoting any number: it contains the first-look
result, the rule changes made afterwards, and what it cannot tell you.

## Run

```powershell
python -m venv .venv
.\.venv\Scripts\python -m pip install -e ".[dev]"
.\.venv\Scripts\python -m pytest -q
.\.venv\Scripts\python -m ruff check .
.\.venv\Scripts\python -m uvicorn app.main:app --port 8000            # http://localhost:8000/health
```
Docker: `docker compose up -d --wait ai` from the repository root (the image build is **unverified**; it uses Python 3.12,
while development used 3.14.7 — [docs/KNOWN_ISSUES.md](../docs/KNOWN_ISSUES.md) KI-018; the service needs no
scientific libraries, so the Python-version question in KI-025 is closed for now).

Environment: `AI_LOG_LEVEL` (default `INFO`); `AI_ENV` (`dev` default, `staging`, `prod`);
`AI_INTERNAL_TOKEN` — shared secret the backend sends as `X-Internal-Token`;
`AI_INTERNAL_TOKEN_PREVIOUS` — optional old secret still accepted during rotation;
`AI_CLASSIFIER_CHAIN` (default `rules-1.0.0`) — comma-separated list of registered classifier versions (e.g., `rules-1.0.0`, `rules-0.9.0`) supporting zero-code rollback.
With `AI_ENV=dev` an empty token **disables** the check (local development only). With any other `AI_ENV` the service
**refuses to start** unless the token is at least 32 characters (KI-033 resolved in S0-06); an unrecognised `AI_ENV`
also refuses to start. To rotate: set `AI_INTERNAL_TOKEN_PREVIOUS` to the old value and `AI_INTERNAL_TOKEN` to the
new one, move the backend to the new value, then clear the previous one.

## Layout

```
app/main.py            application factory
app/api/               health.py, classify.py
app/core/              config.py (environment), security.py (shared secret)
app/schemas/           classify.py (request/response models)
app/classification/    taxonomy, normalize, rules, engine, base
evaluation/            evaluate.py, export_examples.py, data/ (synthetic + manifest.json), report.md, metrics.json
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
