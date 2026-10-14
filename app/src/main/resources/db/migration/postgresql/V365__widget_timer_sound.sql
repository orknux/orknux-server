-- Whether a chat timer this workspace's agents start sounds when it ends. On:
-- a timer somebody asked for is one they want to hear. An agent may choose
-- otherwise for one timer, when the person tells it to. A column with a
-- default, so rollback-floor stays where it is.
ALTER TABLE workspace ADD COLUMN widget_timer_sound boolean NOT NULL DEFAULT true;
