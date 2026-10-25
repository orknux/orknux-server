-- When a picture asked for in Design was drawn: with started_at, how long its
-- model took, which is what the image editor's estimate of the time left is
-- measured from where the model's server reports no progress of its own. Null
-- on everything drawn before this, and on anything not drawn. Additive.
ALTER TABLE design_image_job ADD COLUMN finished_at TIMESTAMPTZ;
