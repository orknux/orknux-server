-- How far the edge of a Magic edit's selection is softened, in the picture's
-- own pixels; null on anything else. The Postgres history's V432. Issue #638.
ALTER TABLE design_image_job ADD COLUMN feather INTEGER;
