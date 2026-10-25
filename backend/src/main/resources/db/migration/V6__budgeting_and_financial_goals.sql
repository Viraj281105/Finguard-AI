-- V6: Budgeting and Financial Goals (v0.7.0).
-- 1. Table `budgets`: User monthly spending limits (category-specific or overall monthly).
-- 2. Table `financial_goals`: Savings goals with targets, current progress, target dates, and status.
-- 3. Table `goal_contributions`: Ledger of contributions toward financial goals.

-- 1. Budgets table
CREATE TABLE budgets (
    id varchar(255) PRIMARY KEY,
    user_id varchar(255) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    year_month varchar(7) NOT NULL,
    category varchar(50),
    amount numeric(38,2) NOT NULL,
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL,
    CONSTRAINT ck_budgets_amount CHECK (amount > 0),
    CONSTRAINT ck_budgets_year_month CHECK (year_month ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    CONSTRAINT ck_budgets_category CHECK (
        category IS NULL OR category IN (
            'GROCERIES', 'DINING', 'TRANSPORT', 'HOUSING', 'UTILITIES',
            'HEALTHCARE', 'INSURANCE', 'SHOPPING', 'SUBSCRIPTIONS', 'EDUCATION',
            'ENTERTAINMENT', 'FEES', 'LOAN_PAYMENTS', 'UNCATEGORIZED'
        )
    )
);

CREATE UNIQUE INDEX uq_budgets_user_month_category ON budgets (user_id, year_month, category) WHERE category IS NOT NULL;
CREATE UNIQUE INDEX uq_budgets_user_month_overall ON budgets (user_id, year_month) WHERE category IS NULL;
CREATE INDEX idx_budgets_user_month ON budgets (user_id, year_month);

-- 2. Financial Goals table
CREATE TABLE financial_goals (
    id varchar(255) PRIMARY KEY,
    user_id varchar(255) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name varchar(255) NOT NULL,
    description text,
    target_amount numeric(38,2) NOT NULL,
    current_amount numeric(38,2) NOT NULL DEFAULT 0.00,
    target_date date,
    status varchar(50) NOT NULL DEFAULT 'ACTIVE',
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL,
    CONSTRAINT ck_goals_target_amount CHECK (target_amount > 0),
    CONSTRAINT ck_goals_current_amount CHECK (current_amount >= 0),
    CONSTRAINT ck_goals_status CHECK (status IN ('ACTIVE', 'COMPLETED', 'ARCHIVED'))
);

CREATE INDEX idx_goals_user_status ON financial_goals (user_id, status);
CREATE INDEX idx_goals_user ON financial_goals (user_id);

-- 3. Goal Contributions table
CREATE TABLE goal_contributions (
    id varchar(255) PRIMARY KEY,
    goal_id varchar(255) NOT NULL REFERENCES financial_goals(id) ON DELETE CASCADE,
    user_id varchar(255) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    amount numeric(38,2) NOT NULL,
    contribution_date date NOT NULL,
    note varchar(500),
    created_at timestamp NOT NULL,
    CONSTRAINT ck_goal_contributions_amount CHECK (amount > 0)
);

CREATE INDEX idx_goal_contributions_goal_date ON goal_contributions (goal_id, contribution_date DESC);
CREATE INDEX idx_goal_contributions_user ON goal_contributions (user_id);
