-- When a trigger's firing log was last written, on the trigger, so the list can
-- be ordered by Last fired. Filled from the log for what is already there.
-- Additive.

ALTER TABLE workflow_trigger ADD COLUMN last_firing_at TIMESTAMPTZ;
UPDATE workflow_trigger t SET last_firing_at = (SELECT max(f.at) FROM trigger_firing f WHERE f.trigger_id = t.id);
