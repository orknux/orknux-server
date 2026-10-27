-- A person kept out of one workspace although a role they hold opens it.
--
-- Membership is decided by roles, which belong to the installation: taking a
-- role away takes away every workspace it opens, and for somebody the
-- directory signs in it cannot be done here at all. This is the workspace's own
-- half - "not this person, not here" - and it leaves their roles and their
-- other workspaces as they were. WorkspaceAccess reads it on every check.
-- Absence is the ordinary member; deleting the row lets them back in.
CREATE TABLE workspace_excluded_user (
    workspace_id BIGINT       NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    username     VARCHAR(120) NOT NULL,
    excluded_at  TIMESTAMPTZ  NOT NULL,
    excluded_by  VARCHAR(120) NOT NULL,
    PRIMARY KEY (workspace_id, username)
);
