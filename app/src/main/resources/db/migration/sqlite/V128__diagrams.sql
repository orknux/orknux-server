-- See the Postgres V450: diagrams in catalogs, a row per node and per edge, a
-- revision counting the saves, and what agents may do with each.
CREATE TABLE diagram_catalog (
    id           INTEGER PRIMARY KEY,
    workspace_id BIGINT       NOT NULL,
    name         VARCHAR(120) NOT NULL,
    created_by   VARCHAR(255) NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_diagram_catalog_name UNIQUE (workspace_id, name),
    constraint diagram_catalog_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE
);

CREATE TABLE diagram (
    id           INTEGER PRIMARY KEY,
    workspace_id BIGINT       NOT NULL,
    catalog_id   BIGINT       NOT NULL,
    name         VARCHAR(200) NOT NULL,
    agent_access VARCHAR(10)  NOT NULL DEFAULT 'CHANGE',
    revision     INTEGER      NOT NULL DEFAULT 1,
    created_by   VARCHAR(255) NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by   VARCHAR(255) NOT NULL,
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_diagram_agent_access CHECK (agent_access IN ('READ', 'CHANGE')),
    constraint diagram_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE,
    constraint diagram_catalog_id_fkey FOREIGN KEY (catalog_id) REFERENCES diagram_catalog(id) ON DELETE CASCADE
);

CREATE INDEX idx_diagram_workspace ON diagram (workspace_id, updated_at);
CREATE INDEX idx_diagram_catalog ON diagram (catalog_id);

CREATE TABLE diagram_node (
    id         INTEGER PRIMARY KEY,
    diagram_id BIGINT       NOT NULL,
    node_key   VARCHAR(64)  NOT NULL,
    position   INTEGER      NOT NULL,
    kind       VARCHAR(20)  NOT NULL,
    label      VARCHAR(500) NOT NULL,
    x          INTEGER      NOT NULL,
    y          INTEGER      NOT NULL,
    w          INTEGER      NOT NULL,
    h          INTEGER      NOT NULL,
    color      VARCHAR(7),
    notes      TEXT,
    parent_key VARCHAR(64),
    CONSTRAINT uq_diagram_node_key UNIQUE (diagram_id, node_key),
    constraint diagram_node_diagram_id_fkey FOREIGN KEY (diagram_id) REFERENCES diagram(id) ON DELETE CASCADE
);

CREATE TABLE diagram_edge (
    id         INTEGER PRIMARY KEY,
    diagram_id BIGINT       NOT NULL,
    edge_key   VARCHAR(64)  NOT NULL,
    position   INTEGER      NOT NULL,
    source_key VARCHAR(64)  NOT NULL,
    target_key VARCHAR(64)  NOT NULL,
    label      VARCHAR(500),
    kind       VARCHAR(20)  NOT NULL,
    CONSTRAINT uq_diagram_edge_key UNIQUE (diagram_id, edge_key),
    constraint diagram_edge_diagram_id_fkey FOREIGN KEY (diagram_id) REFERENCES diagram(id) ON DELETE CASCADE
);
