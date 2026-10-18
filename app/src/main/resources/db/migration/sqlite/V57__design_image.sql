-- A picture drawn on Design's Images page: the prompt, the model and what it was asked for.
CREATE TABLE design_image (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    workspace_id INTEGER       NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    model_id     INTEGER,
    model_name   VARCHAR(255)  NOT NULL,
    prompt       TEXT          NOT NULL,
    size         VARCHAR(40),
    quality      VARCHAR(40),
    style        VARCHAR(40),
    filename     VARCHAR(255)  NOT NULL,
    content_type VARCHAR(120)  NOT NULL,
    size_bytes   INTEGER       NOT NULL,
    location     VARCHAR(1000) NOT NULL,
    drawn_by     VARCHAR(255)  NOT NULL,
    drawn_at     TIMESTAMP     NOT NULL
);

CREATE INDEX ix_design_image_workspace ON design_image (workspace_id);
