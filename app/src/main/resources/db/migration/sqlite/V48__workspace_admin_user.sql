-- See the Postgres V370: a person who administers one workspace, named on it.
CREATE TABLE workspace_admin_user (
    workspace_id BIGINT       NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    username     VARCHAR(120) NOT NULL,
    granted_at   TIMESTAMP    NOT NULL,
    granted_by   VARCHAR(120) NOT NULL,
    PRIMARY KEY (workspace_id, username)
);
