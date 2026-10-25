-- xml_query, svg_measure and pdf_search answer questions about a large file
-- without the file entering the turn: an XPath over an XML or SVG, the boxes and
-- spacing of an SVG's shapes, the pages a PDF's words are on. Built-ins in
-- BuiltInTools.GRANTED beside svg_read and pdf_read, marked Always for the
-- agents that predate them, as V419 did svg_read. Additive.
INSERT INTO agent_required_tool (agent_id, position, name)
SELECT a.id,
       coalesce((SELECT max(r.position) FROM agent_required_tool r WHERE r.agent_id = a.id), -1)
           + row_number() OVER (PARTITION BY a.id ORDER BY n.ord),
       n.name
FROM agent a
JOIN workspace w ON w.id = a.workspace_id
CROSS JOIN (
    SELECT 1 AS ord, 'pdf_search' AS name
    UNION ALL SELECT 2, 'svg_measure'
    UNION ALL SELECT 3, 'xml_query'
) n
WHERE (a.max_tools IS NULL OR w.unsafe_built_in_tools = false)
  AND NOT EXISTS (SELECT 1 FROM agent_required_tool r WHERE r.agent_id = a.id AND r.name = n.name)
  AND NOT EXISTS (SELECT 1 FROM agent_hidden_tool h WHERE h.agent_id = a.id AND h.name = n.name);
