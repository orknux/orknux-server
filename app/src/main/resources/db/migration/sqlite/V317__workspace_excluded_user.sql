-- See the Postgres V637: a person kept out of one workspace although a role
-- they hold opens it.
CREATE TABLE workspace_excluded_user (
    workspace_id BIGINT       NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    username     VARCHAR(120) NOT NULL,
    excluded_at  TIMESTAMP    NOT NULL,
    excluded_by  VARCHAR(120) NOT NULL,
    PRIMARY KEY (workspace_id, username)
);
