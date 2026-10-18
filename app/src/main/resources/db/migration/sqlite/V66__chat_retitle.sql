-- Postgres V388: whether a chat's title was given by a model, and the exchanges since.

ALTER TABLE chat_session ADD COLUMN title_generated BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE chat_session ADD COLUMN turns_since_title INTEGER NOT NULL DEFAULT 0;
