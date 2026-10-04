-- A watcher says which part of its tool's result the condition is held against.
--
-- A JSONPath into the result - `$` for the whole of it, `$.body` for one field -
-- chosen by the agent when it sets the watcher, so a condition meant for a body
-- of 0 is no longer found in a status of 200. Null on the watchers set before
-- it, which read as the whole result, as they always did. Only adds a column.
ALTER TABLE watcher ADD COLUMN tool_result_path varchar(500);
