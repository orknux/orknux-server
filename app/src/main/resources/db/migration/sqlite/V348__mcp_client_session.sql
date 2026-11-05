-- Who reaches a workspace over MCP, from where, and what they called. See the Postgres V668.

CREATE TABLE mcp_client_session (
    id             INTEGER      PRIMARY KEY AUTOINCREMENT,
    workspace_id   INTEGER      NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    session_key    VARCHAR(64)  NOT NULL,
    username       VARCHAR(255) NOT NULL,
    token_id       INTEGER,
    token_name     VARCHAR(255),
    client_name    VARCHAR(255),
    client_version VARCHAR(100),
    user_agent     VARCHAR(500),
    ip_address     VARCHAR(64)  NOT NULL,
    peer_address   VARCHAR(64),
    hostname       VARCHAR(255),
    started_at     TIMESTAMP    NOT NULL,
    last_seen_at   TIMESTAMP    NOT NULL,
    ended_at       TIMESTAMP,
    calls          INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT uk_mcp_client_session_key UNIQUE (session_key)
);

CREATE INDEX ix_mcp_client_session_workspace ON mcp_client_session (workspace_id, last_seen_at);
CREATE INDEX ix_mcp_client_session_seen ON mcp_client_session (last_seen_at);

CREATE TABLE mcp_call (
    id           INTEGER      PRIMARY KEY AUTOINCREMENT,
    session_id   INTEGER      NOT NULL REFERENCES mcp_client_session (id) ON DELETE CASCADE,
    workspace_id INTEGER      NOT NULL,
    method       VARCHAR(100) NOT NULL,
    tool         VARCHAR(255),
    arguments    VARCHAR(1000),
    failed       BOOLEAN      NOT NULL,
    error        VARCHAR(500),
    duration_ms  INTEGER      NOT NULL,
    at           TIMESTAMP    NOT NULL
);

CREATE INDEX ix_mcp_call_session ON mcp_call (session_id, at);
CREATE INDEX ix_mcp_call_workspace ON mcp_call (workspace_id, at);
CREATE INDEX ix_mcp_call_at ON mcp_call (at);
