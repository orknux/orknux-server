-- Every workspace made since V640 gets the General catalogs it should have had.
--
-- V640 gave each workspace that existed then a default image, wireframe and
-- video catalog, and left a workspace made later to get one the first time its
-- catalogs were asked for. That first time is a page load, which asks for the
-- catalogs and the list at once: both found none, and the second to make one
-- failed - on the one-default index, or, for wireframes, because the list is
-- read in a read-only transaction - so every Design page of a new workspace,
-- and of every copy, opened on INTERNAL_ERROR. A workspace now gets them when
-- it is made; this gives them to those made in between.
--
-- A workspace that somehow has a non-default catalog called General is left to
-- the code, which names its default around it. Postgres V646.

INSERT INTO image_catalog (workspace_id, name, is_default, created_by)
SELECT w.id, 'General', 1, 'orknux' FROM workspace w
WHERE NOT EXISTS (SELECT 1 FROM image_catalog c WHERE c.workspace_id = w.id AND (c.is_default = 1 OR c.name = 'General'));

INSERT INTO wireframe_catalog (workspace_id, name, is_default, created_by)
SELECT w.id, 'General', 1, 'orknux' FROM workspace w
WHERE NOT EXISTS (SELECT 1 FROM wireframe_catalog c WHERE c.workspace_id = w.id AND (c.is_default = 1 OR c.name = 'General'));

INSERT INTO video_catalog (workspace_id, name, is_default, created_by)
SELECT w.id, 'General', 1, 'orknux' FROM workspace w
WHERE NOT EXISTS (SELECT 1 FROM video_catalog c WHERE c.workspace_id = w.id AND (c.is_default = 1 OR c.name = 'General'));
