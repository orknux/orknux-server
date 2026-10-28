-- See the Postgres V639: the sheet orknux_* tools marked Always on every agent
-- with Orknux access, less the ones it hides.
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
