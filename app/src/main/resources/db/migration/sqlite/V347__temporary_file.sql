-- Files kept for a few minutes under a key. See the Postgres V667.

CREATE TABLE temporary_file (
    id           INTEGER      PRIMARY KEY AUTOINCREMENT,
    workspace_id INTEGER      NOT NULL,
    session_id   INTEGER      NOT NULL REFERENCES llm_session (id) ON DELETE CASCADE,
    filename     VARCHAR(255) NOT NULL,
    content_type VARCHAR(255) NOT NULL,
    is_binary    BOOLEAN      NOT NULL,
    size         INTEGER      NOT NULL,
    created_at   TIMESTAMP    NOT NULL,
    expires_at   TIMESTAMP    NOT NULL,
    created_by   VARCHAR(255)
);

CREATE INDEX ix_temporary_file_session ON temporary_file (session_id);
CREATE INDEX ix_temporary_file_expires ON temporary_file (expires_at);

CREATE TABLE temporary_file_part (
    file_id INTEGER NOT NULL REFERENCES temporary_file (id) ON DELETE CASCADE,
    part    INTEGER NOT NULL,
    bytes   BLOB    NOT NULL,
    PRIMARY KEY (file_id, part)
);

ALTER TABLE llm_session_store ADD COLUMN temporary_file_id INTEGER REFERENCES temporary_file (id) ON DELETE CASCADE;
