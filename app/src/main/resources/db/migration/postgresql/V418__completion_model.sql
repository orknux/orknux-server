-- Which model suggests how a sentence typed in the chat goes on. Issue #649.
--
-- Its own choice beside the companion and quick chat models: it is asked on
-- every pause in somebody's typing, so it wants to be small and fast, and the
-- model a chat is held with rarely is. Null means nothing is suggested and the
-- composer behaves as it always has. Additive.
ALTER TABLE workspace
    ADD COLUMN completion_model_id BIGINT REFERENCES llm_model (id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_workspace_completion_model_id ON workspace (completion_model_id);
