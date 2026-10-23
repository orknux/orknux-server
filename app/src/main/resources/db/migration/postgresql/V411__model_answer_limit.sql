-- How long one call to this model may take, in minutes, where it differs from
-- the installation's Model Answer Time Limit. Null takes the installation's.
-- Issue #644. Additive.
ALTER TABLE llm_model ADD COLUMN answer_limit_minutes INTEGER;
