-- app_link, a built-in in BuiltInTools.GRANTED: the address this installation
-- is reached at, and a path turned into a link that opens anywhere. On for
-- every agent already, since only a hidden built-in is stored; this marks it
-- Always for the agents that predate it, as V389 did the schedule tools.
-- Additive.

INSERT INTO agent_required_tool (agent_id, position, name)
SELECT a.id,
       coalesce((SELECT max(r.position) FROM agent_required_tool r WHERE r.agent_id = a.id), -1) + 1,
       'app_link'
FROM agent a
JOIN workspace w ON w.id = a.workspace_id
WHERE (a.max_tools IS NULL OR w.unsafe_built_in_tools = false)
  AND NOT EXISTS (SELECT 1 FROM agent_required_tool r WHERE r.agent_id = a.id AND r.name = 'app_link')
  AND NOT EXISTS (SELECT 1 FROM agent_hidden_tool h WHERE h.agent_id = a.id AND h.name = 'app_link');
