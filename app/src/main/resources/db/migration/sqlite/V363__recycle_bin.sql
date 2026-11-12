-- A deleted object, kept so it can be put back, until it is purged. The same as
-- Postgres V683. Additive. See that migration for why.
CREATE TABLE recycle_bin
(
    id                  INTEGER      PRIMARY KEY AUTOINCREMENT,
    workspace_id        integer      NOT NULL,
    kind                varchar(16)  NOT NULL,
    original_id         integer      NOT NULL,
    original_catalog_id integer,
    name                varchar(120) NOT NULL,
    deleted_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_by          varchar(120) NOT NULL,
    snapshot            text         NOT NULL,
    CONSTRAINT recycle_bin_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE
);
CREATE INDEX recycle_bin_workspace_idx ON recycle_bin (workspace_id, deleted_at DESC, id DESC);
CREATE INDEX recycle_bin_deleted_idx ON recycle_bin (deleted_at);
