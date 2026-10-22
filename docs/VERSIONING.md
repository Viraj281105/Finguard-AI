# Versioning and releases

Status: this describes the **agreed convention** ([DECISIONS.md](DECISIONS.md) A-11). No release has been tagged yet and
the repository has no tags; the component versions are not aligned yet (KI-022).

## 1. Scheme

[Semantic Versioning 2.0.0](https://semver.org): `MAJOR.MINOR.PATCH`, with an optional pre-release suffix
(`0.5.0-rc.1`).

- **One product version** covers backend, frontend and AI service. All three are set to the same number at release time.
- **Pre-1.0 (`0.y.z`)**: the public interface may change between minor versions, but every incompatible change is
  recorded in the changelog under **Changed/Removed** and flagged **BREAKING**. Minor (`y`) = a planned release in
  [RELEASE_PLAN.md](RELEASE_PLAN.md). Patch (`z`) = bug fixes only, no new features, no schema change that needs
  attention from operators.
- **1.0.0** = the deployable release. From then on:
  - MAJOR: incompatible API, configuration or schema-behaviour change.
  - MINOR: backwards-compatible features (including additive migrations and new endpoints).
  - PATCH: backwards-compatible fixes.

### What counts as the public interface
1. The HTTP API in [API.md](API.md) (paths, request/response fields, status codes, error body).
2. Configuration: environment variables and their meaning ([DEVELOPMENT.md](DEVELOPMENT.md)).
3. The database schema and migration behaviour for operators ([DATABASE.md](DATABASE.md)).
4. The AI-service contract (`/v1/…`) — versioned **independently** by path: a breaking change adds `/v2/…` and keeps
   `/v1/…` until the backend no longer calls it.

The `/api/*` HTTP API has no path version today and is treated as "v1"; a breaking change after 1.0 introduces `/api/v2`
(D-07, pending confirmation).

## 2. Where versions live

| Place | Today | At release |
|---|---|---|
| `backend/pom.xml` `<version>` | `0.0.1-SNAPSHOT` | `X.Y.Z` |
| `frontend/package.json` `version` | `0.0.0` | `X.Y.Z` |
| `ai/pyproject.toml` `version` and `ai/app/__init__.py` `__version__` | `0.1.0` | `X.Y.Z` (both) |
| Git tag | none | `vX.Y.Z` (annotated) |
| [CHANGELOG.md](CHANGELOG.md) | `[Unreleased]` | dated `[X.Y.Z]` section |

Between releases the backend uses `X.Y.Z-SNAPSHOT` of the *next* version.

## 3. Commits and changelog

- **Conventional Commits**: `type(scope): summary`. Types: `feat`, `fix`, `perf`, `refactor`, `test`, `docs`, `build`,
  `ci`, `chore`. Scopes follow modules: `identity`, `ledger`, `ingestion`, `ai`, `intelligence`, `simulation`, `frontend`,
  `db`, `infra`. Breaking: `feat(api)!: …` or a `BREAKING CHANGE:` footer.
- **Changelog** follows [Keep a Changelog](https://keepachangelog.com): sections *Added, Changed, Deprecated, Removed,
  Fixed, Security*. Written by hand, for readers, in the PR that makes the change; planned work never appears in it.
- Mapping: `feat` → Added/Changed · `fix` → Fixed · security fix → Security · `!`/BREAKING → also prefix the line with **BREAKING**.

## 4. Branching

`main` is always releasable (all suites green). Work happens on `feature/*`, `fix/*`, `docs/*`, `chore/*` branches merged by
pull request. Release preparation is a PR titled `chore(release): vX.Y.Z`. Hotfix for a released version: branch from the tag
(`fix/…`), release as `X.Y.Z+1`, merge back to `main`.

## 5. Release procedure

1. Confirm every acceptance criterion of the version in [RELEASE_PLAN.md](RELEASE_PLAN.md) and the **release gate** at its top.
2. Run `.\scripts\test-all.ps1` on a clean checkout; run the manual checks the version lists (Docker stack for 0.1.0; e2e from 0.11.0).
3. Release PR: set the component versions, move `[Unreleased]` entries to a dated `[X.Y.Z]` section, update
   [PROGRESS.md](PROGRESS.md), [KNOWN_ISSUES.md](KNOWN_ISSUES.md) and the status columns in [ROADMAP.md](ROADMAP.md).
4. Merge to `main`. Tag: `git tag -a vX.Y.Z -m "FinGuard AI X.Y.Z"` and push the tag (an owner action; the tooling used so far has never committed or pushed).
5. Publish release notes (the changelog section). From v1.0.0: also record upgrade steps and any required backup.
6. Start the next `[Unreleased]` section; bump the backend to the next `-SNAPSHOT`.

## 6. Compatibility rules

- **Database:** migrations are forward-only and additive where possible (expand → migrate → contract across versions). A release
  may not require manual data repair. Operators back up before upgrading ([DATABASE.md](DATABASE.md)).
- **API:** within a MINOR line, only additive changes. Deprecations are announced in the changelog one MINOR before removal (after 1.0).
- **Configuration:** a new required variable is a breaking change after 1.0; before 1.0 it is listed under **Changed** with a migration note.
- **Models and rules** (classifier, forecast method, risk model, recommendation thresholds) carry their own version in the
  data they produce; changing one is a MINOR change and is recorded in the changelog with the reason.

## 7. Release naming

Pre-releases `0.5.0-rc.1` may be tagged for owner review. Only the final `vX.Y.Z` tag is a release. Versions are never reused or moved.
