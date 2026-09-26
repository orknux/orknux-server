-- The same backfill as postgresql/V301: turn each session's one agent-setup
-- snapshot (#391) into the AGENT_DETAILS log line #441 writes where an agent
-- starts responding, so sessions written before #441 still open with the setup
-- they were answered under. At the session's start, signed with the agent's
-- name read out of the snapshot, deduped so a session that already carries its
-- line is not doubled. A no-op on a fresh install, whose sessions are empty.
-- The column stays as the latest setup logged, for the reason V301 gives.
-- Issue #441.
INSERT INTO llm_session_event (session_id, kind, actor, content, at)
SELECT s.id,
       'AGENT_DETAILS',
       coalesce(nullif(json_extract(s.agent_details, '$.agent'), ''), 'system'),
       s.agent_details,
       s.created_at
FROM llm_session s
WHERE s.agent_details IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM llm_session_event e
    WHERE e.session_id = s.id
      AND e.kind = 'AGENT_DETAILS'
      AND e.content = s.agent_details
);
