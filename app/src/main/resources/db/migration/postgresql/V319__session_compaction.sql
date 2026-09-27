-- Compacting a turn that is already too long. Issue #521.
--
-- The compaction a workspace could already set measures a stored chat thread
-- before a turn is built. It cannot see this one: an agent that calls forty
-- tools inside a single turn holds all forty answers in memory, was never
-- measured against anything, and the provider refuses the next round outright -
-- "request (69015 tokens) exceeds the available context size (65536 tokens)".
-- The turn ended there, having done all of that work, and whoever asked got
-- nothing back.
--
-- Its own numbers rather than the chat's, because it is a different judgement.
-- The chat's threshold is about when a conversation has grown long enough to be
-- worth shortening; these are about what to salvage from a turn that has already
-- failed, where the choice is between a summary and nothing at all.
--
-- Null takes the installation's, the way every other per-workspace ceiling here
-- works.

ALTER TABLE workspace
    ADD COLUMN session_compaction_keep_turns integer,
    ADD COLUMN session_compaction_summary_tokens integer,
    ADD COLUMN session_compaction_attempts integer,
    ADD COLUMN session_compaction_model_id bigint;

ALTER TABLE workspace
    ADD CONSTRAINT workspace_session_compaction_model_id_fkey
        FOREIGN KEY (session_compaction_model_id) REFERENCES llm_model(id) ON DELETE SET NULL;

COMMENT ON COLUMN workspace.session_compaction_keep_turns IS
    'How many of a turn''s most recent steps survive a compaction word for word; null takes the installation''s.';

COMMENT ON COLUMN workspace.session_compaction_summary_tokens IS
    'How long the summary that replaces the rest may be; null takes the installation''s.';

COMMENT ON COLUMN workspace.session_compaction_attempts IS
    'How many times one turn may be compacted before it gives up; null takes the installation''s.';

COMMENT ON COLUMN workspace.session_compaction_model_id IS
    'Which model writes that summary; null uses the turn''s own model.';

-- The issue number above is wrong and is left wrong on purpose: this file had
-- already run when the tracker numbers came out the other way round, and Flyway
-- checksums an applied migration. Editing it to say #522 is what this comment
-- costs a restart to explain. The work is #522; the session-log compaction that
-- follows it is #523.
