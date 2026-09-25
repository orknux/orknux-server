-- Agent notes used to be drawn in a header above the transcript; #409 removed
-- that header and instead records each note as a NOTE line in the log when it
-- is written. Notes written before #409 have no such line, so they vanished
-- from the page. This turns every existing note into its log line.
--
-- Deduped on session, kind, text and author, so a note that already has its
-- line - one written since #409 landed - is left alone rather than doubled.
-- Issue #409.
INSERT INTO llm_session_event (session_id, kind, actor, content, at)
SELECT n.session_id, 'NOTE', n.written_by, n.note, n.written_at
FROM llm_session_note n
WHERE NOT EXISTS (
    SELECT 1 FROM llm_session_event e
    WHERE e.session_id = n.session_id
      AND e.kind = 'NOTE'
      AND e.content = n.note
      AND e.actor = n.written_by
);
