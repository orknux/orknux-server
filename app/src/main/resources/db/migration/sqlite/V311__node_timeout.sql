-- Postgres V631: how long one attempt of a node's work may take, and the run's copy.
ALTER TABLE workflow_node ADD COLUMN timeout_seconds INTEGER;
ALTER TABLE execution_step ADD COLUMN timeout_seconds INTEGER;
