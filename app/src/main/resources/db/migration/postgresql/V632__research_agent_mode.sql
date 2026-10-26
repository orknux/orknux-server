-- Whether a research ran with one agent for every step or one chosen for each.
-- Null on a research started before there was the choice.

ALTER TABLE research ADD COLUMN agent_mode VARCHAR(16);
