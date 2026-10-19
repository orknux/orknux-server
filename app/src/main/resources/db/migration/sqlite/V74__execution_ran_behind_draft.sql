-- Postgres V396: whether a run started behind the draft.

ALTER TABLE workflow_execution ADD COLUMN ran_behind_draft BOOLEAN;
