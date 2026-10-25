-- See the Postgres V461: orknux_restart_research marked Always on every agent
-- with Orknux access, less the ones that hide it.
INSERT INTO agent_required_tool (agent_id, position, name)
SELECT a.id,
       coalesce((SELECT max(r.position) FROM agent_required_tool r WHERE r.agent_id = a.id), -1) + 1,
       'orknux_restart_research'
FROM agent a
WHERE a.orknux_access = 1
  AND NOT EXISTS (SELECT 1 FROM agent_required_tool r WHERE r.agent_id = a.id AND r.name = 'orknux_restart_research')
  AND NOT EXISTS (SELECT 1 FROM agent_hidden_tool h WHERE h.agent_id = a.id AND h.name = 'orknux_restart_research');
