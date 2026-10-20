-- Whether an agent node hands on what its agent says while it works; see the Postgres V400.
ALTER TABLE workflow_node ADD COLUMN stream_output BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE execution_step ADD COLUMN stream_output BOOLEAN NOT NULL DEFAULT FALSE;
