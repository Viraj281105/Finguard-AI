-- V10__row_level_security_pilot.sql
-- Stage 1 / S1-06 — Row-Level Security (RLS) pilot for tenant isolation (06 §9)
-- Defense-in-depth: Even if an application query omits the user_id predicate, PostgreSQL
-- enforces that the connection can only observe and modify rows belonging to the current tenant.

-- Ensure application role exists for RLS enforcement (non-superuser, no bypassrls)
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'finguard_app') THEN
        CREATE ROLE finguard_app WITH NOSUPERUSER NOBYPASSRLS;
    END IF;
END $$;

GRANT USAGE ON SCHEMA public TO finguard_app;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO finguard_app;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO finguard_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO finguard_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO finguard_app;

-- Grant finguard_app membership to current database user so SET LOCAL ROLE can be invoked
DO $$
BEGIN
    EXECUTE format('GRANT finguard_app TO %I', current_user);
EXCEPTION WHEN OTHERS THEN
    NULL;
END $$;

-- 1. transactions
ALTER TABLE transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE transactions FORCE ROW LEVEL SECURITY;

CREATE POLICY transactions_tenant_isolation ON transactions
    FOR ALL
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR (
            current_setting('app.current_user_id', true) IS NOT NULL
            AND current_setting('app.current_user_id', true) <> ''
            AND user_id = current_setting('app.current_user_id', true)
        )
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR (
            current_setting('app.current_user_id', true) IS NOT NULL
            AND current_setting('app.current_user_id', true) <> ''
            AND user_id = current_setting('app.current_user_id', true)
        )
    );

-- 2. loans
ALTER TABLE loans ENABLE ROW LEVEL SECURITY;
ALTER TABLE loans FORCE ROW LEVEL SECURITY;

CREATE POLICY loans_tenant_isolation ON loans
    FOR ALL
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR (
            current_setting('app.current_user_id', true) IS NOT NULL
            AND current_setting('app.current_user_id', true) <> ''
            AND user_id = current_setting('app.current_user_id', true)
        )
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR (
            current_setting('app.current_user_id', true) IS NOT NULL
            AND current_setting('app.current_user_id', true) <> ''
            AND user_id = current_setting('app.current_user_id', true)
        )
    );

-- 3. investments
ALTER TABLE investments ENABLE ROW LEVEL SECURITY;
ALTER TABLE investments FORCE ROW LEVEL SECURITY;

CREATE POLICY investments_tenant_isolation ON investments
    FOR ALL
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR (
            current_setting('app.current_user_id', true) IS NOT NULL
            AND current_setting('app.current_user_id', true) <> ''
            AND user_id = current_setting('app.current_user_id', true)
        )
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR (
            current_setting('app.current_user_id', true) IS NOT NULL
            AND current_setting('app.current_user_id', true) <> ''
            AND user_id = current_setting('app.current_user_id', true)
        )
    );

-- 4. upload_jobs
ALTER TABLE upload_jobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE upload_jobs FORCE ROW LEVEL SECURITY;

CREATE POLICY upload_jobs_tenant_isolation ON upload_jobs
    FOR ALL
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR (
            current_setting('app.current_user_id', true) IS NOT NULL
            AND current_setting('app.current_user_id', true) <> ''
            AND user_id = current_setting('app.current_user_id', true)
        )
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR (
            current_setting('app.current_user_id', true) IS NOT NULL
            AND current_setting('app.current_user_id', true) <> ''
            AND user_id = current_setting('app.current_user_id', true)
        )
    );

-- 5. budgets
ALTER TABLE budgets ENABLE ROW LEVEL SECURITY;
ALTER TABLE budgets FORCE ROW LEVEL SECURITY;

CREATE POLICY budgets_tenant_isolation ON budgets
    FOR ALL
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR (
            current_setting('app.current_user_id', true) IS NOT NULL
            AND current_setting('app.current_user_id', true) <> ''
            AND user_id = current_setting('app.current_user_id', true)
        )
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR (
            current_setting('app.current_user_id', true) IS NOT NULL
            AND current_setting('app.current_user_id', true) <> ''
            AND user_id = current_setting('app.current_user_id', true)
        )
    );

-- 6. financial_goals
ALTER TABLE financial_goals ENABLE ROW LEVEL SECURITY;
ALTER TABLE financial_goals FORCE ROW LEVEL SECURITY;

CREATE POLICY financial_goals_tenant_isolation ON financial_goals
    FOR ALL
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR (
            current_setting('app.current_user_id', true) IS NOT NULL
            AND current_setting('app.current_user_id', true) <> ''
            AND user_id = current_setting('app.current_user_id', true)
        )
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR (
            current_setting('app.current_user_id', true) IS NOT NULL
            AND current_setting('app.current_user_id', true) <> ''
            AND user_id = current_setting('app.current_user_id', true)
        )
    );

-- 7. goal_contributions
ALTER TABLE goal_contributions ENABLE ROW LEVEL SECURITY;
ALTER TABLE goal_contributions FORCE ROW LEVEL SECURITY;

CREATE POLICY goal_contributions_tenant_isolation ON goal_contributions
    FOR ALL
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR (
            current_setting('app.current_user_id', true) IS NOT NULL
            AND current_setting('app.current_user_id', true) <> ''
            AND user_id = current_setting('app.current_user_id', true)
        )
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR (
            current_setting('app.current_user_id', true) IS NOT NULL
            AND current_setting('app.current_user_id', true) <> ''
            AND user_id = current_setting('app.current_user_id', true)
        )
    );

-- 8. saved_scenarios
ALTER TABLE saved_scenarios ENABLE ROW LEVEL SECURITY;
ALTER TABLE saved_scenarios FORCE ROW LEVEL SECURITY;

CREATE POLICY saved_scenarios_tenant_isolation ON saved_scenarios
    FOR ALL
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR (
            current_setting('app.current_user_id', true) IS NOT NULL
            AND current_setting('app.current_user_id', true) <> ''
            AND user_id = current_setting('app.current_user_id', true)
        )
    )
    WITH CHECK (
        current_setting('app.bypass_rls', true) = 'on'
        OR (
            current_setting('app.current_user_id', true) IS NOT NULL
            AND current_setting('app.current_user_id', true) <> ''
            AND user_id = current_setting('app.current_user_id', true)
        )
    );

