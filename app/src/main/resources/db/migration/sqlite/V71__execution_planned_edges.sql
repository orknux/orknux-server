-- Postgres V393: the edges a run was planned with.

ALTER TABLE workflow_execution ADD COLUMN planned_edges TEXT;
