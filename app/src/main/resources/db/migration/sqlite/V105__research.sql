-- See the Postgres V427: Answer > Research, the modes that say which workflow a
-- research runs, and the flag that keeps a workflow off the list. Issue #659.
ALTER TABLE workflow ADD COLUMN hidden BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE research_mode (
    id           INTEGER PRIMARY KEY,
    workspace_id INTEGER      NOT NULL,
    kind         VARCHAR(16)  NOT NULL,
    name         VARCHAR(100) NOT NULL,
    workflow_id  BIGINT,
    position     INTEGER      NOT NULL DEFAULT 0,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_research_mode_kind CHECK (kind IN ('SHALLOW', 'MEDIUM', 'DEEP', 'CUSTOM')),
    CONSTRAINT uk_research_mode_name UNIQUE (workspace_id, name),
    constraint research_mode_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE
);

CREATE INDEX research_mode_workspace_idx ON research_mode (workspace_id, position);

CREATE TABLE research (
    id            INTEGER PRIMARY KEY,
    workspace_id  INTEGER      NOT NULL,
    question      TEXT         NOT NULL,
    mode_id       BIGINT,
    mode_name     VARCHAR(100) NOT NULL,
    workflow_id   BIGINT       NOT NULL,
    workflow_name VARCHAR(255) NOT NULL,
    model_id      BIGINT,
    model_name    VARCHAR(255),
    language      VARCHAR(16)  NOT NULL,
    output_format VARCHAR(16)  NOT NULL,
    sources       TEXT         NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    execution_id  BIGINT,
    result        TEXT,
    progress      TEXT,
    found_sources TEXT,
    artifact_id   BIGINT,
    error         TEXT,
    started_by    VARCHAR(255) NOT NULL,
    started_at    TIMESTAMP    NOT NULL,
    finished_at   TIMESTAMP,
    CONSTRAINT ck_research_language CHECK (language IN ('ACADEMIC', 'COLLOQUIAL', 'FORMAL')),
    CONSTRAINT ck_research_output_format CHECK (output_format IN ('PDF', 'DOCUMENT')),
    CONSTRAINT ck_research_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED', 'STOPPED')),
    constraint research_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE
);

CREATE INDEX research_workspace_idx ON research (workspace_id, started_at DESC);
CREATE INDEX research_execution_idx ON research (execution_id);
