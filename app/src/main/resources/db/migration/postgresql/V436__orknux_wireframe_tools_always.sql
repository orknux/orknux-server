-- The orknux_* tools added after V424 - the wireframes' six and Research's two -
-- marked Always on every agent with Orknux access that does not hide them, as
-- V424 did for the rest. Arriving at Offer, they sat behind the tool search on an
-- agent with a tool limit, and asked for a mockup it drew an SVG picture instead
-- of a wireframe it never saw. Additive.
WITH orknux_name (ord, name) AS (
  VALUES
    (1, 'orknux_wireframes'),
    (2, 'orknux_wireframe'),
    (3, 'orknux_create_wireframe'),
    (4, 'orknux_change_wireframe'),
    (5, 'orknux_draw_wireframe'),
    (6, 'orknux_wireframe_html'),
    (7, 'orknux_start_research'),
    (8, 'orknux_research')
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
