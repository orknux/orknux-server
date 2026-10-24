-- How hard the model thinks in one chat; null leaves the model's own setting.
-- The postgres copy, V429, carries the reasoning.

ALTER TABLE chat_session ADD COLUMN reasoning_effort varchar(16);
