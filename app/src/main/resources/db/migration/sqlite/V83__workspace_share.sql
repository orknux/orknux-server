-- See the Postgres V405: one workspace lending another a variable catalog or a
-- model provider, used from both without the other's people seeing it.
CREATE TABLE workspace_share (
    id                    INTEGER PRIMARY KEY,
    owner_workspace_id    BIGINT       NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    grantee_workspace_id  BIGINT       NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    kind                  VARCHAR(32)  NOT NULL,
    component_id          BIGINT       NOT NULL,
    created_at            TIMESTAMP    NOT NULL,
    created_by            VARCHAR(255) NOT NULL,
    CONSTRAINT ck_workspace_share_kind CHECK (kind IN ('VARIABLE_CATALOG', 'MODEL_PROVIDER')),
    CONSTRAINT ck_workspace_share_other CHECK (owner_workspace_id <> grantee_workspace_id),
    CONSTRAINT uq_workspace_share UNIQUE (grantee_workspace_id, kind, component_id)
);

CREATE INDEX idx_workspace_share_owner ON workspace_share (owner_workspace_id);
