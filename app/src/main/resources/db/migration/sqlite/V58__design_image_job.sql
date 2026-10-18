-- A picture asked for on Design's Images page and not yet drawn: the queue it waits in.
CREATE TABLE design_image_job (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    workspace_id INTEGER       NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    model_id     INTEGER,
    model_name   VARCHAR(255)  NOT NULL,
    prompt       TEXT          NOT NULL,
    size         VARCHAR(40),
    quality      VARCHAR(40),
    style        VARCHAR(40),
    requested_by VARCHAR(255)  NOT NULL,
    requested_at TIMESTAMP     NOT NULL,
    started_at   TIMESTAMP,
    status       VARCHAR(20)   NOT NULL,
    reason       TEXT,
    image_id     INTEGER,
    CONSTRAINT ck_design_image_job_status CHECK (status IN ('QUEUED', 'DRAWING', 'DRAWN', 'FAILED'))
);

CREATE INDEX ix_design_image_job_workspace ON design_image_job (workspace_id, status);

-- A picture drawn in the image editor is a draft until somebody saves it; those drawn before were kept.
ALTER TABLE design_image ADD COLUMN saved BOOLEAN NOT NULL DEFAULT TRUE;
