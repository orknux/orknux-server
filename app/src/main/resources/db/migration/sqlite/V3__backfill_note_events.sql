-- The same backfill as postgresql/V289: turn every agent note into the NOTE log
-- line #409 records on write, so notes written before #409 still read in the
-- session log. A no-op on a fresh install, whose note table is empty. Deduped
-- so a note that already has its line is not doubled. Issue #409.
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
