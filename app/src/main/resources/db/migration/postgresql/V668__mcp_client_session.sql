-- Who reaches a workspace over MCP, from where, and what they called.
--
-- An MCP client is something running on somebody's machine with a token, and
-- until now nothing said which machines those were or what they did: the
-- audit log has the changes a tool made and nothing about the reading, the
-- address, or which client. A session is one client's connection - opened by
-- `initialize`, carried by the Mcp-Session-Id it is handed - and a call is one
-- thing it asked for.
--
-- ip_address is the client's as the request says it: the first address of
-- X-Forwarded-For where a proxy wrote one, the connection's otherwise, which
-- is then kept in peer_address. For showing, not for trusting.

CREATE TABLE mcp_client_session (
    id             BIGSERIAL    PRIMARY KEY,
    workspace_id   BIGINT       NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    session_key    VARCHAR(64)  NOT NULL,
    username       VARCHAR(255) NOT NULL,
    -- No foreign key: a revoked token's sessions are still history.
    token_id       BIGINT,
    token_name     VARCHAR(255),
    client_name    VARCHAR(255),
    client_version VARCHAR(100),
    user_agent     VARCHAR(500),
    ip_address     VARCHAR(64)  NOT NULL,
    peer_address   VARCHAR(64),
    hostname       VARCHAR(255),
    started_at     TIMESTAMPTZ  NOT NULL,
    last_seen_at   TIMESTAMPTZ  NOT NULL,
    ended_at       TIMESTAMPTZ,
    calls          INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT uk_mcp_client_session_key UNIQUE (session_key)
);

CREATE INDEX ix_mcp_client_session_workspace ON mcp_client_session (workspace_id, last_seen_at);
CREATE INDEX ix_mcp_client_session_seen ON mcp_client_session (last_seen_at);

CREATE TABLE mcp_call (
    id           BIGSERIAL    PRIMARY KEY,
    session_id   BIGINT       NOT NULL REFERENCES mcp_client_session (id) ON DELETE CASCADE,
    workspace_id BIGINT       NOT NULL,
    method       VARCHAR(100) NOT NULL,
    tool         VARCHAR(255),
    arguments    VARCHAR(1000),
    failed       BOOLEAN      NOT NULL,
    error        VARCHAR(500),
    duration_ms  BIGINT       NOT NULL,
    at           TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_mcp_call_session ON mcp_call (session_id, at);
CREATE INDEX ix_mcp_call_workspace ON mcp_call (workspace_id, at);
CREATE INDEX ix_mcp_call_at ON mcp_call (at);
