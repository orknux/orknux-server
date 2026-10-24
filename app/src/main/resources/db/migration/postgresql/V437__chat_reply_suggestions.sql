-- The replies suggested under a chat's latest answer, one per line, and which
-- answer they were made for, so a reload shows them again without asking the
-- completion model a second time. Two nullable columns: additive.
ALTER TABLE chat_session ADD COLUMN reply_suggestions TEXT;
ALTER TABLE chat_session ADD COLUMN reply_suggestions_for VARCHAR(64);
