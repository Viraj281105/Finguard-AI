-- V5: Transaction categorisation (v0.5.0).
-- 1. Normalise existing free-text categories to the canonical taxonomy (contracts/categories.json).
-- 2. Category provenance: review flag, calibration flag, matched rule, timestamp.
-- 3. Constraints that make the taxonomy and the provenance consistent at the database level.
-- 4. Import jobs: classification outcome.
-- Nothing is deleted: a category that cannot be mapped becomes UNCATEGORIZED and the original text is
-- preserved in the transaction note.

-- 1. Provenance columns
ALTER TABLE transactions ADD COLUMN category_needs_review boolean NOT NULL DEFAULT false;
ALTER TABLE transactions ADD COLUMN category_confidence_calibrated boolean NOT NULL DEFAULT false;
ALTER TABLE transactions ADD COLUMN category_rule varchar(64);
ALTER TABLE transactions ADD COLUMN category_updated_at timestamp;

-- 2. Normalise categories. Blank -> no category.
UPDATE transactions SET category = NULL WHERE category IS NOT NULL AND btrim(category) = '';

-- Known labels used by the old frontend dropdown (and their obvious spellings) -> canonical ids.
UPDATE transactions SET category = CASE lower(btrim(category))
    WHEN 'salary' THEN 'INCOME'
    WHEN 'freelance' THEN 'INCOME'
    WHEN 'business' THEN 'INCOME'
    WHEN 'investment returns' THEN 'INCOME'
    WHEN 'income' THEN 'INCOME'
    WHEN 'food' THEN 'DINING'
    WHEN 'dining' THEN 'DINING'
    WHEN 'groceries' THEN 'GROCERIES'
    WHEN 'grocery' THEN 'GROCERIES'
    WHEN 'transport' THEN 'TRANSPORT'
    WHEN 'rent' THEN 'HOUSING'
    WHEN 'housing' THEN 'HOUSING'
    WHEN 'utilities' THEN 'UTILITIES'
    WHEN 'shopping' THEN 'SHOPPING'
    WHEN 'entertainment' THEN 'ENTERTAINMENT'
    WHEN 'healthcare' THEN 'HEALTHCARE'
    WHEN 'health' THEN 'HEALTHCARE'
    WHEN 'education' THEN 'EDUCATION'
    WHEN 'emi' THEN 'LOAN_PAYMENTS'
    WHEN 'other' THEN 'UNCATEGORIZED'
    ELSE category
END
WHERE category IS NOT NULL
  AND lower(btrim(category)) IN ('salary', 'freelance', 'business', 'investment returns', 'income', 'food',
      'dining', 'groceries', 'grocery', 'transport', 'rent', 'housing', 'utilities', 'shopping',
      'entertainment', 'healthcare', 'health', 'education', 'emi', 'other');

-- Values that already are canonical ids (any letter case) are upper-cased.
UPDATE transactions SET category = upper(btrim(category))
WHERE category IS NOT NULL
  AND upper(btrim(category)) IN ('INCOME', 'GROCERIES', 'DINING', 'TRANSPORT', 'HOUSING', 'UTILITIES',
      'HEALTHCARE', 'INSURANCE', 'SHOPPING', 'SUBSCRIPTIONS', 'EDUCATION', 'ENTERTAINMENT', 'FEES',
      'LOAN_PAYMENTS', 'INVESTMENTS', 'TRANSFERS', 'CARD_PAYMENTS', 'REFUNDS', 'UNCATEGORIZED');

-- Anything else: keep the user's text in the note, then mark the category UNCATEGORIZED.
UPDATE transactions
SET note = left(
        CASE WHEN note IS NULL OR note = '' THEN '' ELSE note || ' | ' END
            || 'Previous category: ' || category,
        255),
    category = 'UNCATEGORIZED'
WHERE category IS NOT NULL
  AND category NOT IN ('INCOME', 'GROCERIES', 'DINING', 'TRANSPORT', 'HOUSING', 'UTILITIES', 'HEALTHCARE',
      'INSURANCE', 'SHOPPING', 'SUBSCRIPTIONS', 'EDUCATION', 'ENTERTAINMENT', 'FEES', 'LOAN_PAYMENTS',
      'INVESTMENTS', 'TRANSFERS', 'CARD_PAYMENTS', 'REFUNDS', 'UNCATEGORIZED');

-- Before this release only people (never an automatic process) could set a category, so every
-- existing categorised row is a user's choice and must be protected from automation.
UPDATE transactions SET category_source = 'USER', category_updated_at = created_at
WHERE category IS NOT NULL AND category_source = 'NONE';

-- Rows that claim a category source but have no category were left inconsistent by earlier code
-- (an update that cleared the category kept the old source).
UPDATE transactions SET category_source = 'NONE', category_confidence = NULL, category_model_version = NULL
WHERE category IS NULL AND category_source <> 'NONE';

-- 3. Constraints
ALTER TABLE transactions ADD CONSTRAINT ck_transactions_category CHECK (
    category IS NULL OR category IN (
        'INCOME', 'GROCERIES', 'DINING', 'TRANSPORT', 'HOUSING', 'UTILITIES', 'HEALTHCARE', 'INSURANCE',
        'SHOPPING', 'SUBSCRIPTIONS', 'EDUCATION', 'ENTERTAINMENT', 'FEES', 'LOAN_PAYMENTS',
        'INVESTMENTS', 'TRANSFERS', 'CARD_PAYMENTS', 'REFUNDS', 'UNCATEGORIZED'));

-- A category exists exactly when a source says where it came from.
ALTER TABLE transactions ADD CONSTRAINT ck_transactions_category_provenance CHECK (
    (category_source = 'NONE') = (category IS NULL));

ALTER TABLE transactions ADD CONSTRAINT ck_transactions_category_confidence CHECK (
    category_confidence IS NULL OR (category_confidence >= 0 AND category_confidence <= 1));

-- Review queue / "uncategorised" listing: per user, by source.
CREATE INDEX idx_transactions_user_category_source ON transactions (user_id, category_source);

-- 4. Import jobs: what classification did for this import.
ALTER TABLE upload_jobs ADD COLUMN classification_status varchar(32) NOT NULL DEFAULT 'NOT_ATTEMPTED';
ALTER TABLE upload_jobs ADD COLUMN classified_rows integer NOT NULL DEFAULT 0;
ALTER TABLE upload_jobs ADD CONSTRAINT ck_upload_jobs_classification_status CHECK (
    classification_status IN ('NOT_ATTEMPTED', 'COMPLETED', 'PARTIAL', 'UNAVAILABLE'));
