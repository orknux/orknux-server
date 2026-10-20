-- A stopped picture in Design. The postgres copy, V398, carries the reasoning.
--
-- design_image_job is built again for its wider CHECK, since SQLite cannot
-- change one in place. Nothing points at it, so it is copied, dropped and
-- renamed, and its index made again.

CREATE TABLE design_image_job_new (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    workspace_id  INTEGER       NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    model_id      INTEGER,
    model_name    VARCHAR(255)  NOT NULL,
    prompt        TEXT          NOT NULL,
    size          VARCHAR(40),
    quality       VARCHAR(40),
    style         VARCHAR(40),
    requested_by  VARCHAR(255)  NOT NULL,
    requested_at  TIMESTAMP     NOT NULL,
    started_at    TIMESTAMP,
    status        VARCHAR(20)   NOT NULL,
    reason        TEXT,
    image_id      INTEGER,
    name          VARCHAR(255),
    source_id     VARCHAR(80),
    mask_location VARCHAR(1000),
    upscaler      VARCHAR(255),
    scale         DOUBLE PRECISION,
    strength      DOUBLE PRECISION,
    checkpoint    VARCHAR(255),
    CONSTRAINT ck_design_image_job_status CHECK (status IN ('QUEUED', 'DRAWING', 'DRAWN', 'FAILED', 'STOPPED'))
);

INSERT INTO design_image_job_new (id, workspace_id, model_id, model_name, prompt, size, quality, style, requested_by,
                                  requested_at, started_at, status, reason, image_id, name, source_id, mask_location,
                                  upscaler, scale, strength, checkpoint)
SELECT id, workspace_id, model_id, model_name, prompt, size, quality, style, requested_by,
       requested_at, started_at, status, reason, image_id, name, source_id, mask_location,
       upscaler, scale, strength, checkpoint
FROM design_image_job;

DROP TABLE design_image_job;

ALTER TABLE design_image_job_new RENAME TO design_image_job;

CREATE INDEX ix_design_image_job_workspace ON design_image_job (workspace_id, status);
