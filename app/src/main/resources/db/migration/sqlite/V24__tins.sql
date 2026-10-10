-- Tins, the replacement for the shells. The postgres copy, V346, carries the
-- reasoning.

CREATE TABLE tin
(
    id               integer not null primary key autoincrement,
    name             varchar(255) not null,
    label            varchar(255),
    address          varchar(1000) not null,
    token            varchar(4000),
    created_at       timestamp not null,
    last_modified_at timestamp not null,
    last_modified_by varchar(255) not null,
    constraint uk_tin_name UNIQUE (name)
);

CREATE TABLE tin_session
(
    id                integer not null primary key autoincrement,
    workspace_id      integer not null,
    llm_session_id    integer not null,
    agent_id          integer,
    agent_name        varchar(255),
    tin_kind          varchar(16) not null,
    tin_id            integer,
    tin_name          varchar(255) not null,
    remote_session_id varchar(255) not null,
    status            varchar(32) not null,
    created_at        timestamp not null,
    last_used_at      timestamp,
    closed_at         timestamp,
    constraint tin_session_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE,
    constraint tin_session_llm_session_id_fkey FOREIGN KEY (llm_session_id) REFERENCES llm_session(id) ON DELETE CASCADE,
    constraint ck_tin_session_kind CHECK (tin_kind IN ('LOCAL', 'REMOTE'))
);

CREATE INDEX idx_tin_session_workspace_id ON tin_session (workspace_id);
CREATE INDEX idx_tin_session_llm_session_id ON tin_session (llm_session_id, created_at);

ALTER TABLE agent ADD COLUMN tin_labels TEXT;
