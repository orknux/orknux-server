-- A chat shows what an answer cost unless its person says otherwise.
-- It was off by default, so somebody showing none had not chosen that; every
-- one is turned on, and Preferences turns it off again.

ALTER TABLE app_user ALTER COLUMN chat_cost_shown SET DEFAULT TRUE;
UPDATE app_user SET chat_cost_shown = TRUE;
