-- Ports an agent asked to open out of a box session, and the questions put to
-- a person on its behalf. The postgres copy, V354, carries the reasoning.

CREATE TABLE box_port
(
    id             integer not null primary key autoincrement,
    workspace_id   integer not null,
    llm_session_id integer not null,
    tin_session_id integer not null,
    container_port integer not null,
    reason         text,
    state          varchar(16) not null,
    host_port      integer,
    url            text,
    note           text,
    requested_by   varchar(255),
    created_at     timestamp not null,
    decided_at     timestamp,
    decided_by     varchar(255),
    constraint box_port_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE,
    constraint box_port_llm_session_id_fkey FOREIGN KEY (llm_session_id) REFERENCES llm_session(id) ON DELETE CASCADE,
    constraint box_port_tin_session_id_fkey FOREIGN KEY (tin_session_id) REFERENCES tin_session(id) ON DELETE CASCADE,
    constraint ck_box_port_state CHECK (state IN ('PENDING', 'ENABLED', 'DISABLED', 'REFUSED')),
    constraint uq_box_port UNIQUE (tin_session_id, container_port)
);

CREATE INDEX idx_box_port_llm_session_id ON box_port (llm_session_id, created_at);

CREATE TABLE interaction_request
(
    id             integer not null primary key autoincrement,
    workspace_id   integer not null,
    llm_session_id integer not null,
    kind           varchar(60) not null,
    subject_id     integer,
    asked_by       varchar(255),
    question       text not null,
    options        text not null,
    chosen         varchar(60),
    chosen_by      varchar(255),
    chosen_at      timestamp,
    outcome        text,
    created_at     timestamp not null,
    constraint interaction_request_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE,
    constraint interaction_request_llm_session_id_fkey FOREIGN KEY (llm_session_id) REFERENCES llm_session(id) ON DELETE CASCADE
);

CREATE INDEX idx_interaction_request_llm_session_id ON interaction_request (llm_session_id, created_at);
CREATE INDEX idx_interaction_request_subject ON interaction_request (kind, subject_id);
