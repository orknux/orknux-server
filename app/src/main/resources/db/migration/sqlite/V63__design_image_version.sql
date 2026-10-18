-- A picture changed in the image editor and not saved yet: a version of the picture it changes,
-- by that picture's artifact id, kept off every list until it is saved over it or thrown away.
ALTER TABLE design_image ADD COLUMN version_of VARCHAR(80);
