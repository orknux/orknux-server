-- The orknux_* skill tools - orknux_workspace_skills, orknux_skill,
-- orknux_create_skill and orknux_update_skill, with which an agent writes down
-- a way of working - marked Always on every agent with Orknux access that does
-- not hide them, as V451 did for the diagram tools. Behind the tool search, an
-- agent asked to save a way of working as a skill did not find them and told
-- the person to paste it in by hand. A new agent already starts with them on.
-- Additive.
WITH orknux_name (ord, name) AS (
  VALUES
    (1, 'orknux_workspace_skills'),
    (2, 'orknux_skill'),
    (3, 'orknux_create_skill'),
    (4, 'orknux_update_skill')
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
