-- V4: Resilient ingestion reports, closing balance and period tracking.
-- 1. Add rejected_rows, skipped_rows, row_errors_json to upload_jobs.
-- 2. Add closing_balance, statement_from, statement_to to upload_jobs.

ALTER TABLE upload_jobs ADD COLUMN rejected_rows integer NOT NULL DEFAULT 0;
ALTER TABLE upload_jobs ADD COLUMN skipped_rows integer NOT NULL DEFAULT 0;
ALTER TABLE upload_jobs ADD COLUMN row_errors_json text;
ALTER TABLE upload_jobs ADD COLUMN closing_balance numeric(38,2);
ALTER TABLE upload_jobs ADD COLUMN statement_from date;
ALTER TABLE upload_jobs ADD COLUMN statement_to date;
