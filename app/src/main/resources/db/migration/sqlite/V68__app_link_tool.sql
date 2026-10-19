-- Postgres V390: app_link marked Always for the agents that predate it.

INSERT INTO agent_required_tool (agent_id, position, name)
SELECT a.id,
       coalesce((SELECT max(r.position) FROM agent_required_tool r WHERE r.agent_id = a.id), -1) + 1,
       'app_link'
FROM agent a
JOIN workspace w ON w.id = a.workspace_id
WHERE (a.max_tools IS NULL OR w.unsafe_built_in_tools = 0)
  AND NOT EXISTS (SELECT 1 FROM agent_required_tool r WHERE r.agent_id = a.id AND r.name = 'app_link')
  AND NOT EXISTS (SELECT 1 FROM agent_hidden_tool h WHERE h.agent_id = a.id AND h.name = 'app_link');
