-- See the Postgres V451: the diagram orknux_* tools marked Always on every agent
-- with Orknux access, less the ones it hides, and orknux_delete_diagram hidden
-- on every agent.
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
  WHERE a.orknux_access = 1
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
