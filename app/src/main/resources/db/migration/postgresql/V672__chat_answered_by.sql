-- Who answers a chat when it is not a model of this installation's: CLAUDE_CODE
-- for "My Claude Code", the person's own Claude Code session reached through
-- the Orknux channel plugin. Null for every chat answered by a model or an
-- agent, which is every chat before this. Additive: an older jar ignores it.
ALTER TABLE chat_session ADD COLUMN answered_by VARCHAR(32);
