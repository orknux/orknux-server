-- What a session has cost: the tokens of every model call made into it, and
-- their price where the model carries one; and whether a workspace's session
-- pages show it, on by default. Columns with defaults, so rollback-floor stays.
ALTER TABLE llm_session ADD COLUMN spent_input_tokens BIGINT NOT NULL DEFAULT 0;
ALTER TABLE llm_session ADD COLUMN spent_output_tokens BIGINT NOT NULL DEFAULT 0;
ALTER TABLE llm_session ADD COLUMN spent_cached_input_tokens BIGINT NOT NULL DEFAULT 0;
ALTER TABLE llm_session ADD COLUMN spent_cost NUMERIC(16, 6) NOT NULL DEFAULT 0;
ALTER TABLE llm_session ADD COLUMN spent_partly_unpriced BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE workspace ADD COLUMN session_cost_shown BOOLEAN NOT NULL DEFAULT TRUE;
