-- The cached-input price per million tokens; see the Postgres V399.
ALTER TABLE llm_model ADD COLUMN cached_input_cost_per_million numeric(12,4);
