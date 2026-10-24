-- How far the edge of a Magic edit's selection is softened, in the picture's
-- own pixels, before the model's change is laid over the picture: 0 is a hard
-- edge. Issue #638.
--
-- Nullable: null on anything that is not an edit, and on every edit asked for
-- before this column, which was laid over with a hard edge. Additive: an older
-- jar never reads it.
ALTER TABLE design_image_job ADD COLUMN feather INTEGER;
