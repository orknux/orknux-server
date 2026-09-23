-- A session started from another, for an agent asked by the one in it. Issue #379.

-- An agent that asks another agent handed it a one-shot conversation that was
-- written down nowhere. Now the asked agent gets a session of its own, keyed
-- under the parent's key and titled with what the asking agent called the
-- task, and this is the thread back: the session page lists a session's
-- family and switches between them.
--
-- Set null rather than cascaded when the parent goes, because a subagent's
-- transcript is still a record of what was done.
ALTER TABLE llm_session ADD COLUMN parent_session_id bigint REFERENCES llm_session (id) ON DELETE SET NULL;
ALTER TABLE llm_session ADD COLUMN title varchar(200);

CREATE INDEX llm_session_parent_idx ON llm_session (parent_session_id, created_at, id);
