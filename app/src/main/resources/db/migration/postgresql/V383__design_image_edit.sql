-- A request to change part of a picture: which picture, and the mask saying where.
ALTER TABLE design_image_job ADD COLUMN source_id VARCHAR(80);
ALTER TABLE design_image_job ADD COLUMN mask_location VARCHAR(1000);
