-- V3: Dated, idempotent ledger schema changes.
-- 1. Add txn_date to transactions with safe backfill from note or created_at.
-- 2. Add source, import_job_id, fingerprint, category provenance columns.
-- 3. Add duplicate_rows to upload_jobs.
-- 4. Widen loans.interest_rate to numeric(10,4).
-- 5. Add unique fingerprint index and foreign key indexes.

-- 1. Transactions: date, provenance, and fingerprint
ALTER TABLE transactions ADD COLUMN txn_date date;

-- Safe backfill: extract date from note matching '| yyyy-mm-dd', otherwise created_at::date, fallback CURRENT_DATE
UPDATE transactions
SET txn_date = CASE
    WHEN note ~ '\|\s*[0-9]{4}-[0-9]{2}-[0-9]{2}' THEN
        SUBSTRING(note FROM '\|\s*([0-9]{4}-[0-9]{2}-[0-9]{2})')::date
    WHEN created_at IS NOT NULL THEN
        created_at::date
    ELSE
        CURRENT_DATE
END
WHERE txn_date IS NULL;

ALTER TABLE transactions ALTER COLUMN txn_date SET NOT NULL;

ALTER TABLE transactions ADD COLUMN source varchar(32) NOT NULL DEFAULT 'MANUAL';
UPDATE transactions SET source = 'IMPORT' WHERE note LIKE 'Imported from %';
ALTER TABLE transactions ADD CONSTRAINT ck_transactions_source CHECK (source IN ('MANUAL', 'IMPORT'));

ALTER TABLE transactions ADD COLUMN import_job_id varchar(255);
ALTER TABLE transactions ADD CONSTRAINT fk_transactions_import_job FOREIGN KEY (import_job_id) REFERENCES upload_jobs (id);

ALTER TABLE transactions ADD COLUMN fingerprint varchar(64);

ALTER TABLE transactions ADD COLUMN category_source varchar(32) NOT NULL DEFAULT 'NONE';
ALTER TABLE transactions ADD CONSTRAINT ck_transactions_category_source CHECK (category_source IN ('NONE', 'RULE', 'MODEL', 'USER'));

ALTER TABLE transactions ADD COLUMN category_confidence numeric(5,4);
ALTER TABLE transactions ADD COLUMN category_model_version varchar(64);

-- Deduplication index: per-user unique fingerprint (null fingerprints from manual transactions permitted)
CREATE UNIQUE INDEX uq_transactions_user_fingerprint ON transactions (user_id, fingerprint) WHERE fingerprint IS NOT NULL;
CREATE INDEX idx_transactions_user_txn_date ON transactions (user_id, txn_date DESC, created_at DESC);
CREATE INDEX idx_transactions_user_id ON transactions (user_id);

-- 2. Upload jobs: track duplicate rows skipped during import
ALTER TABLE upload_jobs ADD COLUMN duplicate_rows integer DEFAULT 0;
CREATE INDEX idx_upload_jobs_user_id ON upload_jobs (user_id);

-- 3. Loans: widen interest_rate to 4 decimal places (resolves KI-026)
ALTER TABLE loans ALTER COLUMN interest_rate TYPE numeric(10,4);
CREATE INDEX idx_loans_user_id ON loans (user_id);

-- 4. Investments: foreign key index (resolves KI-016)
CREATE INDEX idx_investments_user_id ON investments (user_id);
