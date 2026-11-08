-- Where a chat was opened, when not in the browser: SHELL for Orknux Shell, whose chats
-- lend the agent the person's computer through terminal_run. Additive.
ALTER TABLE chat_session ADD COLUMN opened_in VARCHAR(32);
