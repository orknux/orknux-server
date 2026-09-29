-- How hard a reasoning model thinks before it answers; null sends nothing.
-- The postgres copy, V331, carries the reasoning.

ALTER TABLE llm_model ADD COLUMN reasoning_effort varchar(16);
