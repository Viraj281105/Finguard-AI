# ADR-007 — Tenant Isolation Hardening, PostgreSQL Row-Level Security Pilot, and Dual-Key JWT Rotation

* **Status:** Accepted (2026-11-01)
* **Deciders:** FinGuard-AI Architecture & Security
* **Target Stage:** Stage 1 (S1-06)
* **Cross-References:** [06-security-and-reliability.md §9](06-security-and-reliability.md#9-row-level-security-design-s1-06-pilot), [08-implementation-roadmap.md §S1-06](08-implementation-roadmap.md#s1-06--tenant-isolation-hardening--key-rotation--p1--5-d), [11-architecture-decision-records.md §ADR-007](11-architecture-decision-records.md#adr-007--tenant-isolation-application-scoping--rls-backstop-pilot)

---

## 1. Context

FinGuard-AI operates as a single-database, shared-schema multi-tenant application where all user-owned domain records (`transactions`, `loans`, `investments`, `upload_jobs`, `budgets`, `financial_goals`, `goal_contributions`, `saved_scenarios`) are partitioned by `user_id`.

Prior to S1-06:
1. **Application-Only Tenant Scoping:** Isolation relied exclusively on application-layer repository queries consistently appending `WHERE user_id = :userId`. A single developer mistake or broken query could leak cross-tenant financial records (IDOR).
2. **Static JWT Signing Keys:** The application used a single static HMAC secret (`jwt.secret`). Rotating this secret required immediate invalidation of all active user sessions or coordinated multi-service deployments with downtime.
3. **Endpoint Security Drift:** As new endpoints were added across business modules, no automated gate verified that every new controller method enforced authentication or tenant scoping.
4. **Defense-in-Depth Headers & CSRF:** Strict Transport Security (HSTS) and cookie protections required formal verification across HTTPS and plain HTTP requests to prevent session hijacking and cross-origin state modification.

---

## 2. Decision

We have adopted a defense-in-depth security model across the data, identity, and transport layers:

### A. PostgreSQL Row-Level Security (RLS) Pilot
1. **Migration `V10__row_level_security_pilot.sql`:**
   - Creates a dedicated non-superuser role `finguard_app` without `BYPASSRLS`.
   - Enables and forces RLS (`ALTER TABLE ... ENABLE ROW LEVEL SECURITY`, `ALTER TABLE ... FORCE ROW LEVEL SECURITY`) across the 8 user-owned domain tables:
     - `transactions`
     - `loans`
     - `investments`
     - `upload_jobs`
     - `budgets`
     - `financial_goals`
     - `goal_contributions`
     - `saved_scenarios`
   - Configures tenant isolation policies:
     ```sql
     CREATE POLICY tenant_isolation_policy ON <table>
         AS RESTRICTIVE
         FOR ALL
         TO finguard_app
         USING (user_id = NULLIF(current_setting('app.current_user_id', true), ''))
         WITH CHECK (user_id = NULLIF(current_setting('app.current_user_id', true), ''));
     ```
2. **Dynamic Transaction-Scoped Tenant Connection Proxy:**
   - Implemented `TenantConnectionProxy`, `TenantAwareDataSource`, and `TenantDataSourceConfig`.
   - On the first SQL execution within a transaction, the proxy executes:
     - `SET LOCAL ROLE finguard_app;`
     - `SELECT set_config('app.current_user_id', ?, true);`
   - Using PostgreSQL `SET LOCAL` guarantees that role and session variable changes automatically revert when the transaction commits or rolls back, preventing pooled connection pollution.
3. **Tenant Context Propagation:**
   - `TenantContext` provides a ThreadLocal container carrying the authenticated `tenantId`.
   - `JwtAuthFilter` initializes `TenantContext.setCurrentTenantId(...)` for every authenticated web request and clears it in a `finally` block.
   - `ImportWorker` propagates the tenant ID onto async worker threads.
   - `PrivacyService` runs user deletion under `TenantContext.callAs(...)`.
   - Maintenance jobs (such as `DataRetentionService.purgeExpiredRowErrors`) run under `TenantContext.callAsSystem(...)` with system bypass permissions.
4. **Verification Gate:**
   - `BrokenRepositoryRlsTest` executes queries deliberately omitting the `user_id` predicate (`SELECT * FROM transactions WHERE description = ...`) and verifies that **zero foreign rows** are returned under RLS.

### B. Dual-Key JWT Rotation and `kid` Header
1. **Key Identifier Header:**
   - `JwtService` now generates JWS headers with an explicit `kid` (Key ID), referencing the currently active signing key.
2. **Multi-Key Configuration:**
   - `JwtProperties` supports `jwt.active-kid` and a dictionary of named keys `jwt.keys.<kid>=<secret>`, while maintaining full backward compatibility with single-key `jwt.secret`.
3. **Dynamic Key Resolution & Grace Window:**
   - `JwtService` uses a dynamic `Locator<Key>` to select the appropriate verification key based on the token's `kid`.
   - During key rotation drills, tokens signed with retired or previous keys verify seamlessly during the transition grace window, and immediately fail once retired.
   - Legacy tokens lacking a `kid` header gracefully fall back to the active key.

### C. Automated Authorization Matrix Gate
- `AuthorizationMatrixTest` inspects live Spring `RequestMappingHandlerMapping` instances dynamically.
- Enforces that 100% of non-public `/api/**` endpoints reject unauthenticated requests with HTTP 401 Unauthorized.
- Whitelists strictly the four authentication endpoints (`/api/auth/register`, `/api/auth/login`, `/api/auth/refresh`, `/api/auth/logout`).

### D. Security Headers & CSRF Verification
- `SecurityHeadersAndCsrfTest` verifies:
  - HSTS response header `Strict-Transport-Security: max-age=31536000; includeSubDomains` on HTTPS requests.
  - `Content-Security-Policy: default-src 'self'; frame-ancestors 'none';`.
  - `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`.
  - Refresh tokens enforce `SameSite=Strict`, `HttpOnly`, and `Path=/api/auth` to protect against CSRF attacks.

---

## 3. Consequences & Trade-offs

### Positive
- **Guaranteed Isolation:** Even if an application query has an accidental IDOR defect, the database engine enforces tenant boundaries.
- **Zero-Downtime Secret Rotation:** Cryptographic keys can be staged, made active, and retired across rolling deployments without logging out active users.
- **Continuous Audit:** Any newly added controller endpoint is automatically caught and validated by the authorization matrix test.

### Considerations & Mitigations
- **PostgreSQL Superuser Bypass:** PostgreSQL superuser accounts (such as `postgres` in Zonky embedded PG or RDS master) bypass RLS even with `FORCE ROW LEVEL SECURITY`. Mitigated by having the proxy switch transaction role to `finguard_app` via `SET LOCAL ROLE finguard_app`.
- **Latency Overhead:** `SET LOCAL` adds minimal execution overhead (< 1-2 ms per transaction), well within the p95 latency regression budget of ≤ 10%.
- **Rollback Strategy:** If RLS causes unforeseen query plan regressions, it can be disabled immediately with zero downtime:
  ```sql
  ALTER TABLE transactions DISABLE ROW LEVEL SECURITY;
  -- and for remaining pilot tables
  ```

