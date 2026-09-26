-- A built-in is on unless somebody turned it off. Issue #455.
--
-- V302 made every tool the server brings itself a name in the agent's grant
-- list, which put them all on one screen - and made the list the only thing
-- that says an agent may use one. That has a fault nobody sees until the next
-- release: a built-in added later is in no agent's list, so it arrives switched
-- off everywhere, silently, and stays off until somebody writes another
-- migration to remember it. The first agent made after V302 by a door that did
-- not know about the list had the same hole: no names, no tools.
--
-- So the list is inverted. What is stored is what an agent may *not* use:
-- agent_hidden_tool holds the built-ins somebody switched off, and everything
-- else is offered. A built-in added in a later release is in nobody's hidden
-- list and is therefore on for every agent and every workspace the moment the
-- server starts, which is what "built-in" ought to have meant all along.
--
-- Nothing changes for anybody on upgrade: every built-in an agent does not
-- currently hold is written into its hidden list, so today's answer for today's
-- agents is exactly preserved - draw_picture included, which was a real grant
-- long before V302 and is off on most agents on purpose. The names then leave
-- agent_granted_tool, because two places saying whether a built-in is on is how
-- the two come to disagree. The Always marks on agent_required_tool stay as
-- they are: they qualify a tool that is on, and say nothing about whether it is.

CREATE TABLE agent_hidden_tool
(
    agent_id bigint       NOT NULL REFERENCES agent (id) ON DELETE CASCADE,
    position integer      NOT NULL,
    name     varchar(255) NOT NULL,
    PRIMARY KEY (agent_id, position)
);

-- Every built-in this agent is not holding, in the order the list declares
-- them, which is the order the form draws the rows in.
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

-- And out of the grant list, whose positions are then closed up: the column is
-- an @OrderColumn, so a gap in it reads back as a null in the middle of the
-- list and Hibernate refuses the row.
DELETE FROM agent_granted_tool
WHERE name IN (
    'note_to_self', 'todo_add', 'todo_list', 'todo_reorder', 'todo_note', 'todo_complete',
    'current_time', 'scratchpad_list', 'scratchpad_read', 'scratchpad_write', 'scratchpad_append',
    'scratchpad_replace', 'scratchpad_search', 'scratchpad_share', 'scratchpad_delete',
    'save_artifact', 'base64_encode', 'base64_decode', 'find_connections', 'ask_agent',
    'finish_answer', 'draw_picture', 'picture_link'
);

-- In two passes, out of the way and back: the key is (agent_id, position) and
-- is checked as each row is written, so renumbering in place would collide with
-- a row that has not moved yet. Nothing sits above the offset, so the first
-- pass cannot collide, and the second lands on 0..n-1, which nothing holds.
UPDATE agent_granted_tool SET position = position + 1000000;

UPDATE agent_granted_tool g
SET position = (
    SELECT count(*) FROM agent_granted_tool o
    WHERE o.agent_id = g.agent_id AND o.position < g.position
);
