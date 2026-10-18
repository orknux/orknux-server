-- Postgres V387: whether a new agent starts with a connection granted.

ALTER TABLE workspace_connection ADD COLUMN grant_new_agents BOOLEAN NOT NULL DEFAULT FALSE;
