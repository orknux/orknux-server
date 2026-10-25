-- The runs a research had before the one it is on now, oldest first, as a JSON
-- list of execution ids.
--
-- A research restarted from one of its steps - from its page, from the
-- orknux_restart_research tool, or by re-running its run on the Runs page -
-- moves onto the new run and keeps the row, so the earlier runs are written
-- here rather than lost. Nullable and new: additive, so an older jar still starts.
ALTER TABLE research ADD COLUMN earlier_runs TEXT;
