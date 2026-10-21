-- What a provider charges for input it writes into its prompt cache - above
-- the input price at Anthropic - and how many tokens a model wrote there each
-- day, so a window's cost can charge them. Null and nought charge them at the
-- input price, as before. Additive.
ALTER TABLE llm_model ADD COLUMN cache_write_cost_per_million NUMERIC(12, 4);
ALTER TABLE model_usage_day ADD COLUMN cache_write_tokens BIGINT NOT NULL DEFAULT 0;
