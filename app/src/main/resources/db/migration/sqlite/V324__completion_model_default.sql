-- Whether a workspace with no completion model of its own uses the installation's; see the Postgres V644.
ALTER TABLE workspace ADD COLUMN completion_model_default BOOLEAN NOT NULL DEFAULT FALSE;
