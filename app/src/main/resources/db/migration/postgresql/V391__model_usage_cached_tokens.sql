-- How much of a model's input its provider served from the prompt cache, per
-- day, beside the input it is part of. Earlier days hold none: it was not
-- recorded, so they read as nothing cached. Additive.

ALTER TABLE model_usage_day ADD COLUMN cached_tokens BIGINT NOT NULL DEFAULT 0;
