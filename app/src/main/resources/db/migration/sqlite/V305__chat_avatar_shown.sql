-- Postgres V625: whether a person's chats draw their avatar beside what they said.

ALTER TABLE app_user ADD COLUMN chat_avatar_shown boolean NOT NULL DEFAULT 1;
