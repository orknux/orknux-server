-- Whether a new agent starts with a connection granted. A default only: the
-- agent's own list is what is read. Additive, so an older jar still starts.

ALTER TABLE workspace_connection ADD COLUMN grant_new_agents BOOLEAN NOT NULL DEFAULT FALSE;
