-- The model that suggests how a sentence goes on; see the Postgres V417.
ALTER TABLE workspace ADD COLUMN completion_model_id INTEGER REFERENCES llm_model (id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_workspace_completion_model_id ON workspace (completion_model_id);
