-- Catalogs for sheets, as V320 gave Design's images, wireframes and videos.
-- Postgres V647.

CREATE TABLE sheet_catalog (
    id           INTEGER PRIMARY KEY,
    workspace_id BIGINT       NOT NULL,
    name         VARCHAR(120) NOT NULL,
    is_default   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by   VARCHAR(255) NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_sheet_catalog_name UNIQUE (workspace_id, name),
    constraint sheet_catalog_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE
);

-- One default per workspace: the one nothing has to be filed in to be in.
CREATE UNIQUE INDEX uq_sheet_catalog_default ON sheet_catalog (workspace_id) WHERE is_default;
ALTER TABLE sheet ADD COLUMN catalog_id BIGINT REFERENCES sheet_catalog (id) ON DELETE SET NULL;
CREATE INDEX idx_sheet_catalog_id ON sheet (catalog_id);

INSERT INTO sheet_catalog (workspace_id, name, is_default, created_by)
SELECT id, 'General', TRUE, 'orknux' FROM workspace;
UPDATE sheet SET catalog_id = (SELECT c.id FROM sheet_catalog c WHERE c.workspace_id = sheet.workspace_id AND c.is_default);
