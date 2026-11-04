-- session_usage: an agent reads what its conversation has cost - the tokens
-- and price its page shows. A built-in in BuiltInTools.GRANTED, on for every
-- agent already, and a new agent starts with it Always; marked Always on every
-- agent that has not hidden it and lacks the mark, in V600's shape so
-- BasicToolsAlwaysTest reads this name after agent_stop's. Additive.
WITH basic_name (ord, name) AS (
  VALUES
    (1, 'session_usage')
),
missing AS (
  SELECT a.id AS agent_id, t.ord, t.name
  FROM agent a
  CROSS JOIN basic_name t
  WHERE NOT EXISTS (SELECT 1 FROM agent_hidden_tool h WHERE h.agent_id = a.id AND h.name = t.name)
    AND NOT EXISTS (SELECT 1 FROM agent_required_tool r WHERE r.agent_id = a.id AND r.name = t.name)
)
INSERT INTO agent_required_tool (agent_id, position, name)
SELECT m.agent_id,
       coalesce((SELECT max(r.position) FROM agent_required_tool r WHERE r.agent_id = m.agent_id), -1)
         + row_number() OVER (PARTITION BY m.agent_id ORDER BY m.ord),
       m.name
FROM missing m;
