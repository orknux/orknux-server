-- A picture asked for in Design can be stopped while it waits or while it is
-- drawn, and is then neither drawn nor failed: STOPPED. A wider CHECK, so an
-- older jar still starts.

ALTER TABLE design_image_job DROP CONSTRAINT ck_design_image_job_status;
ALTER TABLE design_image_job ADD CONSTRAINT ck_design_image_job_status
    CHECK (status IN ('QUEUED', 'DRAWING', 'DRAWN', 'FAILED', 'STOPPED'));
