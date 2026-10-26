-- How many times a node runs in one pass of a run; see the Postgres V620. Issue #661.
ALTER TABLE workflow_node ADD COLUMN repeat_times INTEGER;
ALTER TABLE execution_step ADD COLUMN repeat_times INTEGER;
ALTER TABLE execution_step ADD COLUMN repeats_done INTEGER NOT NULL DEFAULT 0;
