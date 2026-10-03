-- find_connections is offered to every agent, and carried on every turn.
--
-- It used to be offered only to an agent holding more connections than the
-- briefing recites, which made it a tool that came and went with a count
-- nobody sees. Now it is offered whatever the number - none included, where it
-- answers that nothing is held and where a grant is made - like any built-in
-- on the Tools list, and a new agent holds it switched on and Always.
--
-- An agent written before V302 without a ceiling was given no Always marks
-- (it read none), so it holds the tool at Offer: found rather than carried the
-- day it is given a ceiling. This marks it Always on every agent that has not
-- hidden it, appended after whatever it holds (@OrderColumn wants contiguous
-- positions). The one exception is V330's: an agent under a ceiling in a
-- workspace whose unsafe built-in switch is on may have demoted it on purpose,
-- and is left alone.
--
-- Additive: an older jar reads a mark it already knows the name of.

INSERT INTO agent_required_tool (agent_id, position, name)
SELECT a.id,
       coalesce((SELECT max(r.position) FROM agent_required_tool r WHERE r.agent_id = a.id), -1) + 1,
       'find_connections'
FROM agent a
JOIN workspace w ON w.id = a.workspace_id
WHERE (a.max_tools IS NULL OR w.unsafe_built_in_tools = false)
  AND NOT EXISTS (
      SELECT 1 FROM agent_required_tool r WHERE r.agent_id = a.id AND r.name = 'find_connections'
  )
  AND NOT EXISTS (
      SELECT 1 FROM agent_hidden_tool h WHERE h.agent_id = a.id AND h.name = 'find_connections'
  );
