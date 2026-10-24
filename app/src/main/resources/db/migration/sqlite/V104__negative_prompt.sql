-- A negative prompt, a model's default and one picture's own; see the Postgres V426.
ALTER TABLE llm_model ADD COLUMN negative_prompt TEXT;
ALTER TABLE design_image_job ADD COLUMN negative_prompt TEXT;
