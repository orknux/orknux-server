-- See the Postgres V642: a saved artifact holding a wireframe written out as an
-- HTML page, written again whenever the wireframe is saved, one per wireframe,
-- and removed with it.
ALTER TABLE workspace_artifact ADD COLUMN hosted_wireframe_id BIGINT REFERENCES wireframe (id) ON DELETE CASCADE;

CREATE UNIQUE INDEX uq_workspace_artifact_hosted_wireframe ON workspace_artifact (hosted_wireframe_id);
