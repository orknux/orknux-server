-- See the Postgres V411: a model's own answer time limit, null taking the
-- installation's.
ALTER TABLE llm_model ADD COLUMN answer_limit_minutes INTEGER;
