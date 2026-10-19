-- A chat shows when each message was sent unless a workspace says otherwise.
-- It was off by default, so a workspace showing none had not chosen that; every
-- one is turned on, and Workspace Settings turns it off again.

ALTER TABLE workspace ALTER COLUMN chat_show_timestamps SET DEFAULT TRUE;
UPDATE workspace SET chat_show_timestamps = TRUE;
