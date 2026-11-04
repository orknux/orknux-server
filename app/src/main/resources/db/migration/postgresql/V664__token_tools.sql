-- A token's own list of the MCP tools it may call, as a JSON array of names.
-- Null is a token that is not Custom: read and write, or read-only, as read_only says.
ALTER TABLE app_user_token ADD COLUMN tools TEXT;
