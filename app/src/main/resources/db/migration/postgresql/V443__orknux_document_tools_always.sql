-- The orknux_* document tools, marked Always on every agent with Orknux access
-- that does not hide them, as V436 did for the wireframe tools. Arriving at
-- Offer, they would sit behind the tool search on an agent with a tool limit,
-- and an agent asked for a report would write it into the chat instead of a
-- document it never saw. Additive.
WITH orknux_name (ord, name) AS (
  VALUES
    (1, 'orknux_documents'),
    (2, 'orknux_document'),
    (3, 'orknux_search_documents'),
    (4, 'orknux_document_export'),
    (5, 'orknux_create_document'),
    (6, 'orknux_write_document'),
    (7, 'orknux_insert_into_document'),
    (8, 'orknux_append_document_section'),
    (9, 'orknux_add_document_image')
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
