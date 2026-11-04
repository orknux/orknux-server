-- Pictures on a diagram. Postgres V660.
CREATE TABLE diagram_image (
    id           INTEGER PRIMARY KEY,
    diagram_id   INTEGER       NOT NULL REFERENCES diagram (id) ON DELETE CASCADE,
    workspace_id INTEGER       NOT NULL,
    filename     VARCHAR(255)  NOT NULL,
    content_type VARCHAR(100)  NOT NULL,
    size_bytes   INTEGER       NOT NULL,
    location     VARCHAR(1000) NOT NULL,
    uploaded_by  VARCHAR(255)  NOT NULL,
    uploaded_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ix_diagram_image_diagram ON diagram_image (diagram_id);

ALTER TABLE diagram_node ADD COLUMN image TEXT;
