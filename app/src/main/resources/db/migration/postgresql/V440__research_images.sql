-- Whether a research looks for images, and the ones it kept.
--
-- Off by default and for the rows already here, which looked for none. The
-- images kept are a JSON list of where each was found, its caption and the
-- artifact its bytes were saved as, written by Gather as it keeps them. Two
-- new columns, one with a default and one nullable: additive, so an older jar
-- still starts.
ALTER TABLE research ADD COLUMN include_images BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE research ADD COLUMN found_images TEXT;
