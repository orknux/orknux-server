-- An agent step remembers the LLM session it talked into, so a run's page can
-- link the step to the conversation it produced. Null for every step that
-- keeps no session. Issue #387.
ALTER TABLE execution_step ADD COLUMN session_id BIGINT;
