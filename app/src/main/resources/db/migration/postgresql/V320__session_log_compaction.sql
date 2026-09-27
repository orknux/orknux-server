-- Compacting a session's log rather than dropping the end of it. Issue #523.
--
-- A session that runs for days outgrows its model, and what happened until now
-- was silent: the oldest turns fell past the recall budget and were not
-- carried, so the agent forgot the beginning of the conversation and nobody was
-- told. A chat has had the alternative since #286 - the older part is read
-- once, summarised, and the summary is carried in its place - and a session
-- never had it, because a chat's thread is a stored list of messages and a
-- session's is this table.
--
-- Marked rather than deleted. The row stays and the transcript still shows it:
-- the log is a record of what happened, and what is carried to a model is a
-- different question from what is kept. Only the first is narrowed here.

ALTER TABLE llm_session_event
    ADD COLUMN superseded boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN llm_session_event.superseded IS
    'Whether a summary has taken this turn''s place in what is carried to the model; the row is kept either way.';

-- The tail reads are all "this session, not superseded, newest first", and they
-- run on every turn of every conversation. Partial, because the superseded rows
-- are exactly the ones no read here is looking for.
CREATE INDEX llm_session_event_carried_idx
    ON llm_session_event (session_id, at DESC, id DESC)
    WHERE superseded = false;
