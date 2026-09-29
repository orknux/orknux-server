-- finish_answer is carried on every turn wherever the workspace keeps its
-- built-ins fixed.
--
-- Reported from production: an agent under a tool ceiling held finish_answer at
-- Offer, so it was a tool to be found rather than one in front of the model, and
-- the agent promised to check back later and ended its turn without the only
-- thing that brings it back. Marked Always it worked. The agent form drew the
-- row as Always all along, because a fixed built-in reads Always there, while
-- the agent's own list said Offer - the screen and the server disagreed.
--
-- So every agent in a workspace whose unsafe built-in switch is off gets the
-- mark, appended after whatever it holds (@OrderColumn wants contiguous
-- positions). A workspace that switched it on may have demoted it on purpose,
-- and is left alone. AgentAPI keeps the mark on while the switch is off.

INSERT INTO agent_required_tool (agent_id, position, name)
SELECT a.id,
       coalesce((SELECT max(r.position) FROM agent_required_tool r WHERE r.agent_id = a.id), -1) + 1,
       'finish_answer'
FROM agent a
JOIN workspace w ON w.id = a.workspace_id
WHERE w.unsafe_built_in_tools = false
  AND NOT EXISTS (
      SELECT 1 FROM agent_required_tool r WHERE r.agent_id = a.id AND r.name = 'finish_answer'
  );
