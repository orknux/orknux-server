-- Postgres V634: admin workspaces, the flag and what one may read.

ALTER TABLE workspace ADD COLUMN admin_workspace BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE admin_workspace (
    workspace_id INTEGER      NOT NULL PRIMARY KEY REFERENCES workspace (id) ON DELETE CASCADE,
    reach_all    BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at   TIMESTAMP    NOT NULL,
    updated_by   VARCHAR(120) NOT NULL
);

CREATE TABLE admin_workspace_reach (
    admin_workspace_id BIGINT NOT NULL REFERENCES admin_workspace (workspace_id) ON DELETE CASCADE,
    workspace_id       BIGINT NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    PRIMARY KEY (admin_workspace_id, workspace_id)
);

CREATE TABLE admin_workspace_scope (
    admin_workspace_id BIGINT      NOT NULL REFERENCES admin_workspace (workspace_id) ON DELETE CASCADE,
    scope              VARCHAR(32) NOT NULL,
    PRIMARY KEY (admin_workspace_id, scope)
);

CREATE TABLE admin_workspace_tool (
    admin_workspace_id BIGINT      NOT NULL REFERENCES admin_workspace (workspace_id) ON DELETE CASCADE,
    tool               VARCHAR(32) NOT NULL,
    PRIMARY KEY (admin_workspace_id, tool)
);
