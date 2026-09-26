-- A built-in is on unless somebody turned it off. Issue #455.
--
-- The SQLite half of V303; read that one for why. The table is created here
-- rather than only in the baseline because an installation made before this
-- release ran a baseline that did not have it, and `IF NOT EXISTS` lets the
-- same file be harmless on a fresh one, where V1 has already made it.

CREATE TABLE IF NOT EXISTS agent_hidden_tool
(
    agent_id integer      not null,
    position integer      not null,
    name     varchar(255) not null,
    primary key (agent_id, position),
    constraint agent_hidden_tool_agent_id_fkey FOREIGN KEY (agent_id) REFERENCES agent (id) ON DELETE CASCADE
);

INSERT INTO agent_hidden_tool (agent_id, position, name)
SELECT a.id,
       row_number() OVER (PARTITION BY a.id ORDER BY n.ord) - 1,
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
    UNION ALL SELECT 22, 'draw_picture'
    UNION ALL SELECT 23, 'picture_link'
) n
WHERE NOT EXISTS (
    SELECT 1 FROM agent_granted_tool g WHERE g.agent_id = a.id AND g.name = n.name
);

DELETE FROM agent_granted_tool
WHERE name IN (
    'note_to_self', 'todo_add', 'todo_list', 'todo_reorder', 'todo_note', 'todo_complete',
    'current_time', 'scratchpad_list', 'scratchpad_read', 'scratchpad_write', 'scratchpad_append',
    'scratchpad_replace', 'scratchpad_search', 'scratchpad_share', 'scratchpad_delete',
    'save_artifact', 'base64_encode', 'base64_decode', 'find_connections', 'ask_agent',
    'finish_answer', 'draw_picture', 'picture_link'
);

-- Out of the way and back, so the key cannot collide mid-renumber; see V303.
UPDATE agent_granted_tool SET position = position + 1000000;

UPDATE agent_granted_tool
SET position = (
    SELECT count(*) FROM agent_granted_tool o
    WHERE o.agent_id = agent_granted_tool.agent_id AND o.position < agent_granted_tool.position
);
