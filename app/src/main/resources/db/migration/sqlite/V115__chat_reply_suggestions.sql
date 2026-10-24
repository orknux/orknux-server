-- See the Postgres V437: the replies suggested under a chat's latest answer, and which answer they were for.
ALTER TABLE chat_session ADD COLUMN reply_suggestions TEXT;
ALTER TABLE chat_session ADD COLUMN reply_suggestions_for VARCHAR(64);
