-- The server's own tools become names on every agent's grant list. Issue #444.
--
-- An agent's Tools list is the one place somebody reads to see what it may do,
-- and it showed three of the server's built-ins: draw_picture as a name, and
-- finish_answer and picture_link as booleans folded in by the form. Everything
-- else the server brings - the note, the to-do list, the clock, the scratchpad,
-- saving a file, finding a connection, asking another agent - was handed out
-- without asking and could not be seen or switched off. Every one of them is a
-- name in agent_granted_tool now, with the same Hide, Offer, Always control as
-- a workspace's or a plugin's tool; BuiltInTools.kt is the list.
--
-- Nothing changes for an agent that exists. Each gets every built-in it was
-- being handed: the ones that were unconditional go to every agent, and the
-- three that had a switch go only where the switch was on - an agent somebody
-- turned finish_answer off for stays without it. Appended after whatever the
-- agent already holds, because the list is ordered and @OrderColumn wants the
-- positions contiguous; deduped against names already there, since draw_picture
-- may be, and so may any of these on a database this has partly run on.
--
-- Then the same names go on agent_required_tool where the agent has a ceiling
-- of its own: under a ceiling a name not marked Always is found rather than
-- carried, and every one of these used to be carried. An agent without a
-- ceiling reads no marks, so none are written for it - the form remembers the
-- state as Always either way, because for it Always is what "on" meant.
--
-- The three boolean columns go last, once they have been read: the grant list
-- says what they said, and a second place saying it is how the two disagree.

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
