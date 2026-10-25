-- When a picture asked for in Design was drawn; see the Postgres history's V510.
ALTER TABLE design_image_job ADD COLUMN finished_at TIMESTAMP;
