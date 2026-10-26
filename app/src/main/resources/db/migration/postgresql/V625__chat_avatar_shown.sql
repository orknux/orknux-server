-- Whether a person's chats draw their avatar beside what they said; on unless
-- they turn it off under Preferences.

ALTER TABLE app_user ADD COLUMN chat_avatar_shown BOOLEAN NOT NULL DEFAULT TRUE;
