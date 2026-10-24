-- See the Postgres V440: whether a research looks for images, and the ones it kept.
ALTER TABLE research ADD COLUMN include_images BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE research ADD COLUMN found_images TEXT;
