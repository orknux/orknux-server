-- The agent's setup as it stood when the session was first written into, as
-- JSON, so a session's log opens with the context its words were said in.
-- Issue #391.
ALTER TABLE llm_session ADD COLUMN agent_details text;
