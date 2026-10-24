-- See the Postgres V425: one row per wireframe per workspace, its drawing one
-- JSON document, and a revision counting the saves.
CREATE TABLE wireframe (
    id           INTEGER PRIMARY KEY,
    workspace_id BIGINT       NOT NULL,
    name         VARCHAR(200) NOT NULL,
    document     TEXT         NOT NULL,
    revision     INTEGER      NOT NULL DEFAULT 1,
    created_by   VARCHAR(255) NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by   VARCHAR(255) NOT NULL,
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    constraint wireframe_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE
);

CREATE INDEX idx_wireframe_workspace ON wireframe (workspace_id, updated_at);
