-- The name somebody gave a picture before it was drawn; null leaves it to the prompt.
ALTER TABLE design_image_job ADD COLUMN name VARCHAR(255);
