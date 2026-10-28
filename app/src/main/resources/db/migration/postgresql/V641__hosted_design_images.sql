-- A picture drawn in Design hosted as an artifact: a workspace_artifact row - the
-- SAVED-n that agents, tools and links address - that refers to the picture
-- rather than holding a copy of it.
--
-- The row shares the picture's own file: its location is the picture's, kept
-- the same by the one place a Design picture's file is ever replaced
-- (WorkspaceArtifactAPI.replaceBytes), so a picture edited and saved in Design
-- is what the artifact serves next, with no second step and no bytes copied.
-- Removing the artifact - stopping hosting - removes the row and never the file,
-- which is the picture's; removing the picture removes the row with it, by the
-- cascade below, since an artifact hosting nothing would serve a file that is gone.
--
-- One artifact per picture, which is what makes hosting twice show the
-- address it already has. No CHECK touches the column, so the cascade cannot
-- meet one on either engine (#169).
--
-- Additive: a nullable column and an index.
ALTER TABLE workspace_artifact ADD COLUMN hosted_image_id BIGINT REFERENCES design_image (id) ON DELETE CASCADE;

CREATE UNIQUE INDEX uq_workspace_artifact_hosted_image ON workspace_artifact (hosted_image_id);
