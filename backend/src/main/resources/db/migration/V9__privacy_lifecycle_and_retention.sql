-- V9: Privacy Lifecycle, Account Deletion Grace Period, and Append-Only Audit Enforcement (S1-05 — Project J).
-- Recovery plan:
--   DROP TRIGGER IF EXISTS trg_audit_log_append_only ON audit_log;
--   DROP FUNCTION IF EXISTS prevent_audit_log_mutation();
--   DROP INDEX IF EXISTS ix_users_deletion_scheduled_at;
--   ALTER TABLE users DROP COLUMN IF EXISTS deletion_scheduled_at;
--   ALTER TABLE users DROP COLUMN IF EXISTS deletion_requested_at;

-- 1. Account deletion grace-period tracking on users
ALTER TABLE users ADD COLUMN deletion_requested_at timestamptz;
ALTER TABLE users ADD COLUMN deletion_scheduled_at timestamptz;

CREATE INDEX ix_users_deletion_scheduled_at
    ON users (deletion_scheduled_at)
    WHERE deletion_scheduled_at IS NOT NULL;

-- 2. Database-level append-only enforcement on audit_log
CREATE OR REPLACE FUNCTION prevent_audit_log_mutation()
RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only; % operations are prohibited', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_log_append_only
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW
    EXECUTE FUNCTION prevent_audit_log_mutation();

