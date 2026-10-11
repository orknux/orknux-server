-- Ports an agent asked to open out of a box session, and the questions put
-- to a person on its behalf.
--
-- A port is opened only once somebody allows it: the agent's request is a row
-- in PENDING, a person enables, disables or deletes it from the session page,
-- or answers the question the chat shows. REFUSED is a request somebody said
-- no to, kept so the panel can still enable it.
--
-- interaction_request is the general half: a question with a list of options,
-- asked in a conversation, answered by a person. `kind` names the handler that
-- acts on the answer and `subject_id` what it is about - a box_port row for the
-- first kind, `box.port`. Nothing here knows about ports, so the next kind of
-- question is a handler and not a table.
--
-- Additive: older jars do not know the tables.

CREATE TABLE box_port
(
    id             BIGSERIAL PRIMARY KEY,
    workspace_id   BIGINT       NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    llm_session_id BIGINT       NOT NULL REFERENCES llm_session (id) ON DELETE CASCADE,
    tin_session_id BIGINT       NOT NULL REFERENCES tin_session (id) ON DELETE CASCADE,
    container_port INTEGER      NOT NULL,
    reason         TEXT,
    state          VARCHAR(16)  NOT NULL,
    host_port      INTEGER,
    url            TEXT,
    note           TEXT,
    requested_by   VARCHAR(255),
    created_at     TIMESTAMPTZ  NOT NULL,
    decided_at     TIMESTAMPTZ,
    decided_by     VARCHAR(255),
    CONSTRAINT ck_box_port_state CHECK (state IN ('PENDING', 'ENABLED', 'DISABLED', 'REFUSED')),
    CONSTRAINT uq_box_port UNIQUE (tin_session_id, container_port)
);

CREATE INDEX idx_box_port_llm_session_id ON box_port (llm_session_id, created_at);

CREATE TABLE interaction_request
(
    id             BIGSERIAL PRIMARY KEY,
    workspace_id   BIGINT       NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    llm_session_id BIGINT       NOT NULL REFERENCES llm_session (id) ON DELETE CASCADE,
    kind           VARCHAR(60)  NOT NULL,
    subject_id     BIGINT,
    asked_by       VARCHAR(255),
    question       TEXT         NOT NULL,
    options        TEXT         NOT NULL,
    chosen         VARCHAR(60),
    chosen_by      VARCHAR(255),
    chosen_at      TIMESTAMPTZ,
    outcome        TEXT,
    created_at     TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_interaction_request_llm_session_id ON interaction_request (llm_session_id, created_at);
CREATE INDEX idx_interaction_request_subject ON interaction_request (kind, subject_id);
