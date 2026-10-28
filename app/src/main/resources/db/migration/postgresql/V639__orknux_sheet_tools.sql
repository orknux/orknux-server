-- The orknux_* sheet tools on the agents that predate them, marked Always on
-- every agent with Orknux access that does not hide them, as V436 did for the
-- wireframes' and V451 for the diagrams': arriving at Offer, they sit behind
-- the tool search on an agent with a tool limit, and an agent asked to put
-- numbers in a sheet cannot see the tool to. There is no tool that deletes a
-- sheet, so nothing is hidden. Additive.
WITH orknux_name (ord, name) AS (
  VALUES
    (1, 'orknux_sheets'),
    (2, 'orknux_sheet'),
    (3, 'orknux_create_sheet'),
    (4, 'orknux_write_sheet_cells'),
    (5, 'orknux_change_sheet_tabs')
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
