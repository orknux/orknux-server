-- Postgres V397: the strength and checkpoint of a light redraw asked for in Design.

ALTER TABLE design_image_job ADD COLUMN strength DOUBLE PRECISION;
ALTER TABLE design_image_job ADD COLUMN checkpoint VARCHAR(255);
