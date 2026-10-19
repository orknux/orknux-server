-- Postgres V395: when a trigger's firing log was last written, on the trigger.

ALTER TABLE workflow_trigger ADD COLUMN last_firing_at TIMESTAMP;
UPDATE workflow_trigger SET last_firing_at = (SELECT max(f.at) FROM trigger_firing f WHERE f.trigger_id = workflow_trigger.id);
