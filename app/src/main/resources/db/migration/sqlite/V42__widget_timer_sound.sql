-- Whether a chat timer this workspace's agents start sounds when it ends; on
-- by default. Postgres V365.
ALTER TABLE workspace ADD COLUMN widget_timer_sound BOOLEAN NOT NULL DEFAULT 1;
