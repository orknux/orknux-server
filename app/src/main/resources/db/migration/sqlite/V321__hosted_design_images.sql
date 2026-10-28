-- See the Postgres V641: a saved artifact that hosts a picture drawn in Design,
-- sharing its file rather than copying it, one per picture, and removed with it.
ALTER TABLE workspace_artifact ADD COLUMN hosted_image_id BIGINT REFERENCES design_image (id) ON DELETE CASCADE;

CREATE UNIQUE INDEX uq_workspace_artifact_hosted_image ON workspace_artifact (hosted_image_id);
