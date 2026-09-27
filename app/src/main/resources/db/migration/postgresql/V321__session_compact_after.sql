-- How long a session's log may grow before it is compacted. Issue #523.
--
-- Its own threshold rather than the chat's, because they are different
-- conversations. A chat's is one a person is having and is measured in the
-- dozens of turns; a session's is one an agent is having, runs for days, and
-- carries tool results a chat never sees. One number behind both would be right
-- for whichever was set last.
--
-- Null takes the installation's number, and zero turns it off - which is what
-- an installation that would rather forget the beginning than pay for a summary
-- sets it to.

ALTER TABLE workspace
    ADD COLUMN session_compact_after_tokens integer;

COMMENT ON COLUMN workspace.session_compact_after_tokens IS
    'How long a session log may grow before it is compacted; null takes the installation''s, zero is off.';
