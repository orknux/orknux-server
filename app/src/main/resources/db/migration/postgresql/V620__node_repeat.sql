-- How many times a node runs in one pass of a run, each time handed what the
-- last one produced; null is once. Issue #661. Additive: an older jar never
-- reads the column and runs every node once, as it always did.
ALTER TABLE workflow_node ADD COLUMN repeat_times INTEGER;

-- The run's own copy, as with the retry policy, and how many of them it has
-- finished, so a repeat that parks picks up at the right one.
ALTER TABLE execution_step ADD COLUMN repeat_times INTEGER;
ALTER TABLE execution_step ADD COLUMN repeats_done INTEGER NOT NULL DEFAULT 0;
