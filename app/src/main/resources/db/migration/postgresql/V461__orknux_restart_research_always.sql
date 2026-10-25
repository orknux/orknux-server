-- orknux_restart_research marked Always on every agent with Orknux access that
-- does not hide it, as V436 did for the Research tools before it: arriving at
-- Offer, it sits behind the tool search on an agent with a tool limit. Additive.
INSERT INTO agent_required_tool (agent_id, position, name)
SELECT a.id,
       coalesce((SELECT max(r.position) FROM agent_required_tool r WHERE r.agent_id = a.id), -1) + 1,
       'orknux_restart_research'
FROM agent a
WHERE a.orknux_access = true
  AND NOT EXISTS (SELECT 1 FROM agent_required_tool r WHERE r.agent_id = a.id AND r.name = 'orknux_restart_research')
  AND NOT EXISTS (SELECT 1 FROM agent_hidden_tool h WHERE h.agent_id = a.id AND h.name = 'orknux_restart_research');
