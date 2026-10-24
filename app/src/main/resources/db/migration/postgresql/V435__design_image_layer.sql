-- A picture's layers in the image editor, kept with the picture or with one of
-- its unsaved versions, by that one's artifact id - a run's or a task's picture
-- as well as Design's own, since the editor opens any of them. The picture's
-- own file stays the layers flattened, which is what everything else reads;
-- these rows are what the editor opens again. Each layer's pixels are a PNG in
-- the attachment store. The row with picture = TRUE is the picture itself
-- under the layers, at layer_order 0. A new table: additive.
CREATE TABLE design_image_layer (
    id           BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT        NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    image_id     VARCHAR(80)   NOT NULL,
    layer_order  INTEGER       NOT NULL,
    picture      BOOLEAN       NOT NULL,
    name         VARCHAR(255),
    visible      BOOLEAN       NOT NULL,
    size_bytes   BIGINT        NOT NULL,
    location     VARCHAR(1000) NOT NULL
);

CREATE INDEX ix_design_image_layer_image ON design_image_layer (workspace_id, image_id);
