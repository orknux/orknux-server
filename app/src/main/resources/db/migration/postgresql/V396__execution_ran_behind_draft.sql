-- Whether a run started while the draft had changes the publication it ran did
-- not: an older version than the one being edited then. Decided at the start,
-- so later edits do not make an old run of every run. Null on earlier runs.
-- Additive.

ALTER TABLE workflow_execution ADD COLUMN ran_behind_draft BOOLEAN;
