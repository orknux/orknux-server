-- See the Postgres V640: a catalog table per kind, a General default for each
-- workspace marked by is_default, and a nullable catalog_id on what is filed,
-- null meaning the default. Existing images, wireframes and videos are filed in
-- the General made here.

CREATE TABLE image_catalog (
    id           INTEGER PRIMARY KEY,
    workspace_id BIGINT       NOT NULL,
    name         VARCHAR(120) NOT NULL,
    is_default   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by   VARCHAR(255) NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_image_catalog_name UNIQUE (workspace_id, name),
    constraint image_catalog_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE
);

-- One default per workspace: the one nothing has to be filed in to be in.
CREATE UNIQUE INDEX uq_image_catalog_default ON image_catalog (workspace_id) WHERE is_default;
ALTER TABLE design_image ADD COLUMN catalog_id BIGINT REFERENCES image_catalog (id) ON DELETE SET NULL;
ALTER TABLE execution_picture ADD COLUMN catalog_id BIGINT REFERENCES image_catalog (id) ON DELETE SET NULL;
ALTER TABLE task_picture ADD COLUMN catalog_id BIGINT REFERENCES image_catalog (id) ON DELETE SET NULL;
ALTER TABLE design_image_job ADD COLUMN catalog_id BIGINT REFERENCES image_catalog (id) ON DELETE SET NULL;

INSERT INTO image_catalog (workspace_id, name, is_default, created_by)
SELECT id, 'General', TRUE, 'orknux' FROM workspace;
UPDATE design_image SET catalog_id = (SELECT c.id FROM image_catalog c WHERE c.workspace_id = design_image.workspace_id AND c.is_default);
UPDATE execution_picture SET catalog_id = (SELECT c.id FROM image_catalog c WHERE c.workspace_id = execution_picture.workspace_id AND c.is_default);
UPDATE task_picture SET catalog_id = (SELECT c.id FROM image_catalog c WHERE c.workspace_id = task_picture.workspace_id AND c.is_default);

CREATE TABLE wireframe_catalog (
    id           INTEGER PRIMARY KEY,
    workspace_id BIGINT       NOT NULL,
    name         VARCHAR(120) NOT NULL,
    is_default   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by   VARCHAR(255) NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_wireframe_catalog_name UNIQUE (workspace_id, name),
    constraint wireframe_catalog_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE
);

-- One default per workspace: the one nothing has to be filed in to be in.
CREATE UNIQUE INDEX uq_wireframe_catalog_default ON wireframe_catalog (workspace_id) WHERE is_default;
ALTER TABLE wireframe ADD COLUMN catalog_id BIGINT REFERENCES wireframe_catalog (id) ON DELETE SET NULL;

INSERT INTO wireframe_catalog (workspace_id, name, is_default, created_by)
SELECT id, 'General', TRUE, 'orknux' FROM workspace;
UPDATE wireframe SET catalog_id = (SELECT c.id FROM wireframe_catalog c WHERE c.workspace_id = wireframe.workspace_id AND c.is_default);

CREATE TABLE video_catalog (
    id           INTEGER PRIMARY KEY,
    workspace_id BIGINT       NOT NULL,
    name         VARCHAR(120) NOT NULL,
    is_default   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by   VARCHAR(255) NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_video_catalog_name UNIQUE (workspace_id, name),
    constraint video_catalog_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE
);

-- One default per workspace: the one nothing has to be filed in to be in.
CREATE UNIQUE INDEX uq_video_catalog_default ON video_catalog (workspace_id) WHERE is_default;
ALTER TABLE design_video ADD COLUMN catalog_id BIGINT REFERENCES video_catalog (id) ON DELETE SET NULL;

INSERT INTO video_catalog (workspace_id, name, is_default, created_by)
SELECT id, 'General', TRUE, 'orknux' FROM workspace;
UPDATE design_video SET catalog_id = (SELECT c.id FROM video_catalog c WHERE c.workspace_id = design_video.workspace_id AND c.is_default);
