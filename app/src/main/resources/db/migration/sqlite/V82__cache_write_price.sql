-- See the Postgres V404: the price of writing into a prompt cache, and the
-- tokens written each day.
ALTER TABLE llm_model ADD COLUMN cache_write_cost_per_million NUMERIC(12, 4);
ALTER TABLE model_usage_day ADD COLUMN cache_write_tokens BIGINT NOT NULL DEFAULT 0;
