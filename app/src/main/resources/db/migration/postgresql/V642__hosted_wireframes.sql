-- A wireframe hosted as an HTML page: a workspace_artifact row - the SAVED-n
-- that agents, tools and links address - holding the wireframe written out as
-- one page (WireframeHtml), and written again whenever the wireframe is saved,
-- from any door (WireframeAPI.write), so its address always serves the
-- drawing as it now is.
--
-- Unlike a hosted picture (V641) the row holds a file of its own: a wireframe
-- has no file to share, only a drawing the page is made from. Removing the
-- wireframe removes the row with it, by the cascade, and WireframeAPI removes
-- the file first, since a cascade cannot reach the disk.
--
-- One artifact per wireframe, which is what makes hosting twice show the
-- address it already has. No CHECK touches the column (#169).
--
-- Additive: a nullable column and an index.
ALTER TABLE workspace_artifact ADD COLUMN hosted_wireframe_id BIGINT REFERENCES wireframe (id) ON DELETE CASCADE;

CREATE UNIQUE INDEX uq_workspace_artifact_hosted_wireframe ON workspace_artifact (hosted_wireframe_id);
