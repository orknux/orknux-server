-- orknux_check_diagram and orknux_route_diagram, the diagram tools that find
-- what makes a drawing hard to read and route its lines around the boxes,
-- marked Always on every agent with Orknux access that does not hide them, as
-- V451 did for the other diagram tools: an agent told to check a diagram
-- before answering must be able to see the tool to. Additive.
WITH orknux_name (ord, name) AS (
  VALUES
    (1, 'orknux_check_diagram'),
    (2, 'orknux_route_diagram')
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
