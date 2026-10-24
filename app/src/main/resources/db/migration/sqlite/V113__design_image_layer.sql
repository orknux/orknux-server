-- See the Postgres V435: a picture's layers in the image editor, kept with the picture or a version of it.
CREATE TABLE design_image_layer (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    workspace_id INTEGER       NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    image_id     VARCHAR(80)   NOT NULL,
    layer_order  INTEGER       NOT NULL,
    picture      BOOLEAN       NOT NULL,
    name         VARCHAR(255),
    visible      BOOLEAN       NOT NULL,
    size_bytes   INTEGER       NOT NULL,
    location     VARCHAR(1000) NOT NULL
);

CREATE INDEX ix_design_image_layer_image ON design_image_layer (workspace_id, image_id);
