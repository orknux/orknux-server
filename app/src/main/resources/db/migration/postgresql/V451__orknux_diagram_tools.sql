-- The orknux_* diagram tools on the agents that predate them.
--
-- The seven that read, search, export and draw are marked Always on every agent
-- with Orknux access that does not hide them, as V436 did for the wireframes':
-- arriving at Offer, they sit behind the tool search on an agent with a tool
-- limit, and an agent told to look in the diagrams cannot see the tool to.
--
-- orknux_delete_diagram is the other way round: hidden on every agent, with
-- Orknux access or without, because deleting a diagram loses what people drew
-- and its owner turns it on one agent at a time. A new agent starts the same way
-- (AgentAPI). Additive.
WITH orknux_name (ord, name) AS (
  VALUES
    (1, 'orknux_diagrams'),
    (2, 'orknux_diagram'),
    (3, 'orknux_diagram_node'),
    (4, 'orknux_diagram_search'),
    (5, 'orknux_diagram_export'),
    (6, 'orknux_create_diagram'),
    (7, 'orknux_change_diagram')
),
missing AS (
  SELECT a.id AS agent_id, t.ord, t.name
  FROM agent a
  CROSS JOIN orknux_name t
  WHERE a.orknux_access = true
    AND NOT EXISTS (SELECT 1 FROM agent_required_tool r WHERE r.agent_id = a.id AND r.name = t.name)
    AND NOT EXISTS (SELECT 1 FROM agent_hidden_tool h WHERE h.agent_id = a.id AND h.name = t.name)
)
INSERT INTO agent_required_tool (agent_id, position, name)
SELECT m.agent_id,
       coalesce((SELECT max(r.position) FROM agent_required_tool r WHERE r.agent_id = m.agent_id), -1)
         + row_number() OVER (PARTITION BY m.agent_id ORDER BY m.ord),
       m.name
FROM missing m;

INSERT INTO agent_hidden_tool (agent_id, position, name)
SELECT a.id,
       coalesce((SELECT max(h.position) FROM agent_hidden_tool h WHERE h.agent_id = a.id), -1) + 1,
       'orknux_delete_diagram'
FROM agent a
WHERE NOT EXISTS (SELECT 1 FROM agent_hidden_tool h WHERE h.agent_id = a.id AND h.name = 'orknux_delete_diagram');
