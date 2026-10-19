-- Postgres V391: the input a model's provider served from its prompt cache, per day.

ALTER TABLE model_usage_day ADD COLUMN cached_tokens INTEGER NOT NULL DEFAULT 0;
