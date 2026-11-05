-- Files kept for a few minutes under a key, for the tools that take one.
--
-- An MCP client - or an agent - hands a tool a file by key: jira_attach, a
-- Slack upload. The file is kept only as long as it takes to pass it on, so
-- it is kept here with the time it goes, and nothing about it outlives that.
-- In the database rather than the attachment store, so every replica reads
-- the same file and nothing is left on a disk when it goes.
--
-- The bytes are in temporary_file_part, in pieces, for the reason
-- server_release_part is: pgjdbc reads a bytea whole, and a file may be a
-- hundred megabytes.

CREATE TABLE temporary_file (
    id           BIGSERIAL    PRIMARY KEY,
    workspace_id BIGINT       NOT NULL,
    session_id   BIGINT       NOT NULL REFERENCES llm_session (id) ON DELETE CASCADE,
    filename     VARCHAR(255) NOT NULL,
    content_type VARCHAR(255) NOT NULL,
    is_binary    BOOLEAN      NOT NULL,
    size         BIGINT       NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    expires_at   TIMESTAMPTZ  NOT NULL,
    created_by   VARCHAR(255)
);

CREATE INDEX ix_temporary_file_session ON temporary_file (session_id);
CREATE INDEX ix_temporary_file_expires ON temporary_file (expires_at);

CREATE TABLE temporary_file_part (
    file_id BIGINT  NOT NULL REFERENCES temporary_file (id) ON DELETE CASCADE,
    part    INTEGER NOT NULL,
    bytes   BYTEA   NOT NULL,
    PRIMARY KEY (file_id, part)
);

-- A key in a session's store that stands for one: the row holds no value of
-- its own, as one standing for a chat attachment does not.
ALTER TABLE llm_session_store ADD COLUMN temporary_file_id BIGINT REFERENCES temporary_file (id) ON DELETE CASCADE;
