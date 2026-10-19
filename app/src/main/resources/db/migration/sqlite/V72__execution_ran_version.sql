-- Postgres V394: which copy of its workflow a run ran.

ALTER TABLE workflow_execution ADD COLUMN ran_draft BOOLEAN;
ALTER TABLE workflow_execution ADD COLUMN publication_id INTEGER;
