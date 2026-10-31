-- Whether a person's chats show how fast the model is writing; on unless they
-- turn it off under Preferences.

ALTER TABLE app_user ADD COLUMN chat_speed_shown BOOLEAN NOT NULL DEFAULT TRUE;
