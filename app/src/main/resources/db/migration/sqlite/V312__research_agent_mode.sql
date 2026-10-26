-- Postgres V632: whether a research ran with one agent for every step or one chosen for each.

ALTER TABLE research ADD COLUMN agent_mode VARCHAR(16);
