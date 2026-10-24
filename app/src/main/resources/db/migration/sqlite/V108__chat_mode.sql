-- Postgres V430: how an agent works in one chat - PLAN, AUTO, or null for the
-- way every chat worked before. Issue #653.

ALTER TABLE chat_session ADD COLUMN chat_mode VARCHAR(16);
