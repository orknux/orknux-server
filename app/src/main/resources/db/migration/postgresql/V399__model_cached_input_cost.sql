-- What a provider charges for input it served from its prompt cache, per
-- million tokens. OpenAI and Azure bill it at a fraction of the input price,
-- and with no price of its own it was costed at the full one. Null keeps it
-- at the input price, as every model costed it until now.
ALTER TABLE llm_model ADD COLUMN cached_input_cost_per_million NUMERIC(12, 4);
