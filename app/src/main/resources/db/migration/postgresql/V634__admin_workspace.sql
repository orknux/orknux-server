-- Admin workspaces: a workspace whose agents may read other workspaces, and
-- reach a handful of installation-wide admin reads, each switched here. Only
-- installation administrators see one (WorkspaceAccess.canSee), and only they
-- switch it, so the flag is on the workspace row the access check already has.
-- Additive: a column with a default and four new tables.

ALTER TABLE workspace ADD COLUMN admin_workspace BOOLEAN NOT NULL DEFAULT FALSE;

-- What an admin workspace may read. Kept when the flag is switched off, so
-- switching it on again finds what was chosen before.
CREATE TABLE admin_workspace (
    workspace_id BIGINT       NOT NULL PRIMARY KEY REFERENCES workspace (id) ON DELETE CASCADE,
    -- Every workspace, or only those in admin_workspace_reach.
    reach_all    BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at   TIMESTAMPTZ  NOT NULL,
    updated_by   VARCHAR(120) NOT NULL
);

-- The chosen workspaces, read only while reach_all is false. A workspace
-- deleted leaves the list with it, so an id reused later is never reached.
CREATE TABLE admin_workspace_reach (
    admin_workspace_id BIGINT NOT NULL REFERENCES admin_workspace (workspace_id) ON DELETE CASCADE,
    workspace_id       BIGINT NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    PRIMARY KEY (admin_workspace_id, workspace_id)
);

-- What may be read in those workspaces, one row per kind switched on: a kind
-- with no row is refused, so one added in a later release starts off.
CREATE TABLE admin_workspace_scope (
    admin_workspace_id BIGINT      NOT NULL REFERENCES admin_workspace (workspace_id) ON DELETE CASCADE,
    scope              VARCHAR(32) NOT NULL,
    PRIMARY KEY (admin_workspace_id, scope)
);

-- Which installation-wide admin reads its agents are offered, the same way.
CREATE TABLE admin_workspace_tool (
    admin_workspace_id BIGINT      NOT NULL REFERENCES admin_workspace (workspace_id) ON DELETE CASCADE,
    tool               VARCHAR(32) NOT NULL,
    PRIMARY KEY (admin_workspace_id, tool)
);
