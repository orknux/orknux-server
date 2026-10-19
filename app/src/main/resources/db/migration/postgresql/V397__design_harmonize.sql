-- On a Design request, how strongly a light redraw of the whole picture
-- changes it and the WebUI checkpoint it is redrawn with. Nullable columns, so
-- an older jar still starts.

ALTER TABLE design_image_job ADD COLUMN strength DOUBLE PRECISION;
ALTER TABLE design_image_job ADD COLUMN checkpoint VARCHAR(255);
