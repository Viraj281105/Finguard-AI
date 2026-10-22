# FinGuard AI documentation

These documents describe the repository **as it is**, plus clearly labelled plans. Code and tests are the
source of truth; if a document disagrees with them, the document is wrong — fix it in the same change.

## Status vocabulary (used in every document)

| Label | Meaning |
|---|---|
| **Implemented & tested** | Code exists and an automated test in this repository exercises it. Results were observed on 2026-10-22 (see [PROGRESS.md](PROGRESS.md)). |
| **Implemented, unverified** | Code or configuration exists but was never run or has no test (for example anything that needs Docker). |
| **Planned** | Does not exist. Described only in [ROADMAP.md](ROADMAP.md) / [RELEASE_PLAN.md](RELEASE_PLAN.md). |

## Index

| Document | Read it to learn… |
|---|---|
| [ROADMAP.md](ROADMAP.md) | Where the project is going: version sequence, dependencies, priorities, milestones |
| [RELEASE_PLAN.md](RELEASE_PLAN.md) | Scope, requirements and acceptance criteria of every planned version |
| [PROGRESS.md](PROGRESS.md) | What is done, what is verified, blockers, the next step |
| [KNOWN_ISSUES.md](KNOWN_ISSUES.md) | Confirmed defects, unverified risks, technical debt, each mapped to a version |
| [DECISIONS.md](DECISIONS.md) | Decisions taken, and open decisions that need an owner's answer |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Modules, dependency rules, data flows, security model |
| [API.md](API.md) | Every implemented HTTP endpoint, with contracts and error format |
| [DATABASE.md](DATABASE.md) | Schema, Flyway rules, integrity rules |
| [FINANCIAL_RULES.md](FINANCIAL_RULES.md) | How money, dates and signs are represented; what is and is not modelled |
| [DEVELOPMENT.md](DEVELOPMENT.md) | Setup, configuration, commands, workflow |
| [TESTING.md](TESTING.md) | Test suites, how to run them, coverage gaps |
| [VERSIONING.md](VERSIONING.md) | Versioning scheme and release procedure |
| [CHANGELOG.md](CHANGELOG.md) | What changed, per version |

Component notes: [../ai/README.md](../ai/README.md) (Python service), [../frontend/README.md](../frontend/README.md).

## Maintenance rules

1. A change that alters behaviour, an endpoint, the schema, configuration or a command updates the matching
   document in the same pull request.
2. Never describe planned work in the present tense. Move it to the roadmap or label it **Planned**.
3. Numbers (test counts, versions, ports) are copied from real output. Re-run the command before editing them.
4. Every user-visible change gets a [CHANGELOG.md](CHANGELOG.md) line under `[Unreleased]`.
