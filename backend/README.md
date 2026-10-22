# FinGuard AI — Backend 🛡️

Spring Boot 3.5 modular monolith built on Java 21, Spring Security 6.5, Hibernate 6.6, and Flyway 11.

## Architecture & Package Structure

The backend is structured by business domain with clear boundaries enforced by ArchUnit tests ([ArchitectureTest.java](src/test/java/com/finguard/backend/ArchitectureTest.java)):

```text
src/main/java/com/finguard/backend/
├── common/                  # Cross-cutting infrastructure (errors, CORS, async executor)
├── identity/                # Authentication, JWT issuance/validation, user management
├── ledger/                  # Financial ledger domain
│   ├── transactions/        # Transaction entities, repository, service, controller, and DTOs
│   ├── investments/         # Investment holdings, repository, service, controller, and DTOs
│   └── loans/               # Loan obligations, repository, service, controller, and DTOs
├── ingestion/               # Bank statement CSV ingestion pipeline
│   ├── parser/              # Bank statement parsers for HDFC, ICICI, SBI, Axis, Kotak
│   ├── service/             # Async background import worker and upload job repository
│   └── web/                 # Upload controller and status responses
├── ai/                      # HTTP client for Python AI service integration
└── BackendApplication.java  # Spring Boot entry point
```

## Database & Migrations

- Flyway manages all schema changes in `src/main/resources/db/migration/`.
- Hibernate runs in `validate` mode (`spring.jpa.hibernate.ddl-auto=validate`) to verify that entity mappings strictly match the schema at startup.
- Dev datasource connects to PostgreSQL on host port 5433 (per `docker-compose.yml`).
- Unit and integration tests run against embedded PostgreSQL 15.19 without requiring a Docker daemon.

## Running Tests

```powershell
.\mvnw test
```
