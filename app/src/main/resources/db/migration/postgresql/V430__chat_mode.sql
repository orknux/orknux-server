-- How an agent works in one chat: PLAN, AUTO, or null for the way every chat
-- worked before - switched from the composer. Issue #653.
--
-- A nullable column with no CHECK: the values are the ChatMode enum's, read
-- by name, and an older jar that does not know the column never reads it.
-- Additive, so the rollback floor stays where it is.

ALTER TABLE chat_session ADD COLUMN chat_mode VARCHAR(16);
