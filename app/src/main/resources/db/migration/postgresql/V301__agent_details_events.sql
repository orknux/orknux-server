-- The agent's setup becomes a line in the log, at each point it changed.
--
-- #391 kept one snapshot of the agent's setup on the session, written by the
-- first agent to answer in it and never rewritten, and the page drew it once
-- above the transcript. A session is not one agent's: a Slack thread's session
-- is answered by whichever agent node a run points at it, and an agent is
-- edited between turns, so that one account was right about the first turn and
-- silently wrong about the rest. #441 writes an AGENT_DETAILS line into the
-- transcript wherever an agent starts responding with a setup that differs from
-- the last one logged, and the page draws each where it falls.
--
-- Sessions written before #441 have the snapshot and no line, so the page -
-- which now draws only the lines - would show nothing for them. This turns each
-- existing snapshot into one line at the session's start, signed with the
-- agent's name read out of the snapshot, so nothing that was on screen
-- disappears. At `created_at` rather than the first event's moment, so it
-- sorts before everything said. Deduped on session and content, so a session
-- that already carries its line is left alone rather than doubled.
--
-- The column stays. It is now the latest setup logged - what the next turn is
-- compared against to decide whether another line is due, and what the
-- session's own `agentDetails` answers - so a session's row and its log agree
-- by construction. Issue #441.
INSERT INTO llm_session_event (session_id, kind, actor, content, at)
SELECT s.id,
       'AGENT_DETAILS',
       coalesce(nullif(s.agent_details::jsonb ->> 'agent', ''), 'system'),
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
