-- See the Postgres V638: one row per sheet per workspace, its workbook one JSON
-- document, and a revision counting the saves.
CREATE TABLE sheet (
    id           INTEGER PRIMARY KEY,
    workspace_id BIGINT       NOT NULL,
    name         VARCHAR(200) NOT NULL,
    workbook     TEXT         NOT NULL,
    revision     INTEGER      NOT NULL DEFAULT 1,
    created_by   VARCHAR(255) NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by   VARCHAR(255) NOT NULL,
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    constraint sheet_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE
);

CREATE INDEX idx_sheet_workspace ON sheet (workspace_id, updated_at);
