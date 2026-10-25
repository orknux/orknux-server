-- See the Postgres V580: the orkbox_* tools marked Always on every agent that
-- holds a box label, so an existing agent carries them as it did.
WITH box_name (ord, name) AS (
  VALUES
    (1, 'orkbox_list'),
    (2, 'orkbox_open'),
    (3, 'orkbox_expose'),
    (4, 'orkbox_ports'),
    (5, 'orkbox_sessions'),
    (6, 'orkbox_close'),
    (7, 'orkbox_extend'),
    (8, 'orkbox_exec'),
    (9, 'orkbox_execution'),
    (10, 'orkbox_output'),
    (11, 'orkbox_stdin'),
    (12, 'orkbox_signal'),
    (13, 'orkbox_files'),
    (14, 'orkbox_read'),
    (15, 'orkbox_write'),
    (16, 'orkbox_edit'),
    (17, 'orkbox_stat'),
    (18, 'orkbox_delete'),
    (19, 'orkbox_move'),
    (20, 'orkbox_undo'),
    (21, 'orkbox_redo'),
    (22, 'orkbox_search'),
    (23, 'orkbox_log'),
    (24, 'orkbox_export'),
    (25, 'orkbox_import'),
    (26, 'orkbox_fetch_artifact')
),
missing AS (
  SELECT a.id AS agent_id, t.ord, t.name
  FROM agent a
  CROSS JOIN box_name t
  WHERE a.tin_labels IS NOT NULL AND trim(a.tin_labels) <> ''
    AND NOT EXISTS (SELECT 1 FROM agent_required_tool r WHERE r.agent_id = a.id AND r.name = t.name)
)
INSERT INTO agent_required_tool (agent_id, position, name)
SELECT m.agent_id,
       coalesce((SELECT max(r.position) FROM agent_required_tool r WHERE r.agent_id = m.agent_id), -1)
         + row_number() OVER (PARTITION BY m.agent_id ORDER BY m.ord),
       m.name
FROM missing m;
