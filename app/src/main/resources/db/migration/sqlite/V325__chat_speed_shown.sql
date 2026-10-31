-- Whether a person's chats show how fast the model is writing; on unless they
-- turn it off under Preferences. Postgres V645.

ALTER TABLE app_user ADD COLUMN chat_speed_shown boolean NOT NULL DEFAULT 1;
