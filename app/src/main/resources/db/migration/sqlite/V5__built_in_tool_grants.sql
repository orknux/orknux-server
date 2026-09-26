-- The same move as postgresql/V302: every built-in the server used to hand an
-- agent without asking becomes a name on its grant list, the three that had a
-- boolean go only where the boolean was on, an agent with a ceiling gets them
-- marked Always so nothing it carried is now found, and the three columns go.
-- Issue #444.
--
-- A data migration rather than a line in V1, for the reason V2 gives: these are
-- rows already on somebody's disk. And the one place the baseline still says
-- something the entity no longer has - V1 declares artifact_access,
-- finish_access and picture_link_access - is deliberate: this file reads them
-- to decide which names an agent gets and then drops them, and a fresh
-- installation runs V1 and then this, so the columns have to be there for it
-- to read and drop. Written as though they had never existed, V1 would leave
-- this file referring to columns a new database does not have, and it would
-- fail on the installation that has nothing to migrate.
--
-- The SQL is the same text as V302's: booleans are 0 and 1 here and read as
-- true and false in a WHERE, window functions and derived tables are SQLite's
-- since 3.25, and DROP COLUMN since 3.35 - the driver ships 3.51.

INSERT INTO agent_granted_tool (agent_id, position, name)
SELECT a.id,
       coalesce((SELECT max(g.position) FROM agent_granted_tool g WHERE g.agent_id = a.id), -1)
           + row_number() OVER (PARTITION BY a.id ORDER BY n.ord),
       n.name
FROM agent a
CROSS JOIN (
    SELECT 1 AS ord, 'note_to_self' AS name
    UNION ALL SELECT 2, 'todo_add'
    UNION ALL SELECT 3, 'todo_list'
    UNION ALL SELECT 4, 'todo_reorder'
    UNION ALL SELECT 5, 'todo_note'
    UNION ALL SELECT 6, 'todo_complete'
    UNION ALL SELECT 7, 'current_time'
    UNION ALL SELECT 8, 'scratchpad_list'
    UNION ALL SELECT 9, 'scratchpad_read'
    UNION ALL SELECT 10, 'scratchpad_write'
    UNION ALL SELECT 11, 'scratchpad_append'
    UNION ALL SELECT 12, 'scratchpad_replace'
    UNION ALL SELECT 13, 'scratchpad_search'
    UNION ALL SELECT 14, 'scratchpad_share'
    UNION ALL SELECT 15, 'scratchpad_delete'
    UNION ALL SELECT 16, 'save_artifact'
    UNION ALL SELECT 17, 'base64_encode'
    UNION ALL SELECT 18, 'base64_decode'
    UNION ALL SELECT 19, 'find_connections'
    UNION ALL SELECT 20, 'ask_agent'
    UNION ALL SELECT 21, 'finish_answer'
    UNION ALL SELECT 22, 'picture_link'
) n
WHERE NOT EXISTS (SELECT 1 FROM agent_granted_tool g WHERE g.agent_id = a.id AND g.name = n.name)
  AND (n.name <> 'finish_answer' OR a.finish_access)
  AND (n.name <> 'picture_link' OR a.picture_link_access)
  AND (n.name NOT IN ('save_artifact', 'base64_encode', 'base64_decode') OR a.artifact_access);

INSERT INTO agent_required_tool (agent_id, position, name)
SELECT a.id,
       coalesce((SELECT max(r.position) FROM agent_required_tool r WHERE r.agent_id = a.id), -1)
           + row_number() OVER (PARTITION BY a.id ORDER BY g.position),
       g.name
FROM agent a
JOIN agent_granted_tool g ON g.agent_id = a.id
WHERE a.max_tools IS NOT NULL
  AND g.name IN (
      'note_to_self', 'todo_add', 'todo_list', 'todo_reorder', 'todo_note', 'todo_complete',
      'current_time',
      'scratchpad_list', 'scratchpad_read', 'scratchpad_write', 'scratchpad_append',
      'scratchpad_replace', 'scratchpad_search', 'scratchpad_share', 'scratchpad_delete',
      'save_artifact', 'base64_encode', 'base64_decode',
      'find_connections', 'ask_agent', 'finish_answer', 'draw_picture', 'picture_link'
  )
  AND NOT EXISTS (SELECT 1 FROM agent_required_tool r WHERE r.agent_id = a.id AND r.name = g.name);

ALTER TABLE agent DROP COLUMN artifact_access;
ALTER TABLE agent DROP COLUMN finish_access;
ALTER TABLE agent DROP COLUMN picture_link_access;
