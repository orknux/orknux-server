-- See the Postgres V442: document catalogs, documents - their content
-- ProseMirror JSON, with a plain-text copy for the search and a revision
-- counting the saves - and the pictures each shows, by artifact id.
CREATE TABLE document_catalog (
    id           INTEGER PRIMARY KEY,
    workspace_id BIGINT       NOT NULL,
    name         VARCHAR(120) NOT NULL,
    created_by   VARCHAR(255) NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    constraint document_catalog_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE,
    CONSTRAINT uq_document_catalog_name UNIQUE (workspace_id, name)
);

CREATE TABLE design_document (
    id           INTEGER PRIMARY KEY,
    workspace_id BIGINT       NOT NULL,
    catalog_id   BIGINT       NOT NULL,
    name         VARCHAR(200) NOT NULL,
    content      TEXT         NOT NULL,
    plain_text   TEXT         NOT NULL DEFAULT '',
    page_size    VARCHAR(10)  NOT NULL DEFAULT 'A4',
    revision     INTEGER      NOT NULL DEFAULT 1,
    created_by   VARCHAR(255) NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by   VARCHAR(255) NOT NULL,
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    constraint design_document_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE,
    constraint design_document_catalog_id_fkey FOREIGN KEY (catalog_id) REFERENCES document_catalog(id) ON DELETE CASCADE,
    CONSTRAINT ck_design_document_page_size CHECK (page_size IN ('A4', 'LETTER'))
);

CREATE INDEX idx_design_document_workspace ON design_document (workspace_id, updated_at);
CREATE INDEX idx_design_document_catalog ON design_document (catalog_id);

CREATE TABLE design_document_image (
    id          INTEGER PRIMARY KEY,
    document_id BIGINT      NOT NULL,
    artifact_id VARCHAR(64) NOT NULL,
    constraint design_document_image_document_id_fkey FOREIGN KEY (document_id) REFERENCES design_document(id) ON DELETE CASCADE
);

CREATE INDEX idx_design_document_image_document ON design_document_image (document_id);
CREATE INDEX idx_design_document_image_artifact ON design_document_image (artifact_id);
