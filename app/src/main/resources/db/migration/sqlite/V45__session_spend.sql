-- What a session has cost, and whether session pages show it. Postgres V367.
ALTER TABLE llm_session ADD COLUMN spent_input_tokens INTEGER NOT NULL DEFAULT 0;
ALTER TABLE llm_session ADD COLUMN spent_output_tokens INTEGER NOT NULL DEFAULT 0;
ALTER TABLE llm_session ADD COLUMN spent_cached_input_tokens INTEGER NOT NULL DEFAULT 0;
ALTER TABLE llm_session ADD COLUMN spent_cost NUMERIC(16, 6) NOT NULL DEFAULT 0;
ALTER TABLE llm_session ADD COLUMN spent_partly_unpriced BOOLEAN NOT NULL DEFAULT 0;
ALTER TABLE workspace ADD COLUMN session_cost_shown BOOLEAN NOT NULL DEFAULT 1;
