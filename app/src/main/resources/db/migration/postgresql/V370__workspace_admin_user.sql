-- A person who administers one workspace, named on it directly - beside the
-- roles that administer it (workspace_admin_role), which an installation
-- administrator assigns. This is the half a workspace decides for itself: who
-- among the people already in it may change its settings. Absence is the
-- ordinary member; there is no row for "user".
CREATE TABLE workspace_admin_user (
    workspace_id BIGINT       NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    username     VARCHAR(120) NOT NULL,
    granted_at   TIMESTAMPTZ  NOT NULL,
    granted_by   VARCHAR(120) NOT NULL,
    PRIMARY KEY (workspace_id, username)
);
