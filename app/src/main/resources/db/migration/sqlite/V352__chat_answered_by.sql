-- The same as Postgres V672: who answers a chat when it is not a model of this
-- installation's - CLAUDE_CODE for "My Claude Code". Null for every other chat.
ALTER TABLE chat_session ADD COLUMN answered_by VARCHAR(32);
