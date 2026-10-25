-- See the Postgres V600: every built-in a new agent starts at Always marked
-- Always on every agent that holds it and lacks the mark, so the basics -
-- scratchpads, to-dos, notes, the clock, the skill tools - travel again.
WITH basic_name (ord, name) AS (
  VALUES
    (1, 'note_to_self'),
    (2, 'todo_add'),
    (3, 'todo_list'),
    (4, 'todo_reorder'),
    (5, 'todo_note'),
    (6, 'todo_complete'),
    (7, 'current_time'),
    (8, 'app_link'),
    (9, 'timer_set'),
    (10, 'timer_list'),
    (11, 'timer_cancel'),
    (12, 'watcher_set'),
    (13, 'watcher_list'),
    (14, 'watcher_finish'),
    (15, 'watcher_update'),
    (16, 'schedule_set'),
    (17, 'schedule_list'),
    (18, 'schedule_cancel'),
    (19, 'skill_list'),
    (20, 'skill_load'),
    (21, 'skill_search'),
    (22, 'scratchpad_list'),
    (23, 'scratchpad_read'),
    (24, 'scratchpad_write'),
    (25, 'scratchpad_append'),
    (26, 'scratchpad_replace'),
    (27, 'scratchpad_search'),
    (28, 'scratchpad_delete'),
    (29, 'save_artifact'),
    (30, 'base64_encode'),
    (31, 'base64_decode'),
    (32, 'zip_files'),
    (33, 'zip_extract'),
    (34, 'pdf_fromHtml'),
    (35, 'pdf_fromHtmlZip'),
    (36, 'pdf_read'),
    (37, 'sheet_read'),
    (38, 'markdown_toText'),
    (39, 'date_today'),
    (40, 'date_now'),
    (41, 'date_describe'),
    (42, 'date_shift'),
    (43, 'date_shiftBusinessDays'),
    (44, 'date_between'),
    (45, 'date_businessDaysBetween'),
    (46, 'date_isBusinessHours'),
    (47, 'find_connections'),
    (48, 'ask_agent'),
    (49, 'ask_person'),
    (50, 'agent_asks'),
    (51, 'agent_list'),
    (52, 'agent_wait'),
    (53, 'finish_answer')
),
missing AS (
  SELECT a.id AS agent_id, t.ord, t.name
  FROM agent a
  CROSS JOIN basic_name t
  WHERE NOT EXISTS (SELECT 1 FROM agent_hidden_tool h WHERE h.agent_id = a.id AND h.name = t.name)
    AND NOT EXISTS (SELECT 1 FROM agent_required_tool r WHERE r.agent_id = a.id AND r.name = t.name)
)
INSERT INTO agent_required_tool (agent_id, position, name)
SELECT m.agent_id,
       coalesce((SELECT max(r.position) FROM agent_required_tool r WHERE r.agent_id = m.agent_id), -1)
         + row_number() OVER (PARTITION BY m.agent_id ORDER BY m.ord),
       m.name
FROM missing m;
