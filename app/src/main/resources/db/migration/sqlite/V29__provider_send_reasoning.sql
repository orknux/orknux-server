-- Whether a provider is sent the model's own earlier thinking.
-- The postgres copy, V351, carries the reasoning.

ALTER TABLE model_provider ADD COLUMN send_reasoning BOOLEAN NOT NULL DEFAULT FALSE;
