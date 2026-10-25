-- V7: Saved what-if scenarios (v0.9.0).
-- A saved scenario stores the user-chosen adjustments (request_json) and a snapshot of the result that was
-- calculated when it was saved (result_json) together with the model version. It stores no ledger data:
-- re-running recalculates from the current ledger and is compared with the snapshot.
CREATE TABLE saved_scenarios (
    id varchar(255) PRIMARY KEY,
    user_id varchar(255) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name varchar(80) NOT NULL,
    horizon_months integer NOT NULL,
    request_json text NOT NULL,
    result_json text NOT NULL,
    method_version varchar(80) NOT NULL,
    created_at timestamp NOT NULL,
    CONSTRAINT ck_saved_scenarios_horizon CHECK (horizon_months BETWEEN 1 AND 12),
    CONSTRAINT ck_saved_scenarios_name CHECK (length(btrim(name)) > 0)
);

CREATE INDEX idx_saved_scenarios_user_created ON saved_scenarios (user_id, created_at DESC);
