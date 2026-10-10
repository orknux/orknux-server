-- Tins: orktin instances an agent can open a sandbox session on, the
-- replacement for the shells.
--
-- `tin` holds the remote ones an administrator registered; the local tin is
-- embedded in this process and configured in installation_setting, so it has
-- no row. The token is a credential and is kept encrypted by SecretConverter,
-- like every other.
--
-- `tin_session` is one sandbox session an agent opened, recorded so a
-- conversation can show which it used and read their logs. It belongs to the
-- conversation and the workspace, and goes with either. tin_id and agent_id
-- carry no foreign key on purpose, as the watcher's agent_id does not: a row is
-- history, and a tin or an agent removed later should not take the record of
-- what was run with it. tin_id is null for the local tin, which tin_kind says.
--
-- And agent.tin_labels, the grant: which tins an agent may use, by label.
-- Null is none.
--
-- Additive throughout - two new tables and a nullable column - so
-- rollback-floor stays where it is.

CREATE TABLE tin
(
    id               BIGSERIAL PRIMARY KEY,
    name             VARCHAR(255)  NOT NULL,
    label            VARCHAR(255),
    address          VARCHAR(1000) NOT NULL,
    token            VARCHAR(4000),
    created_at       TIMESTAMPTZ   NOT NULL,
    last_modified_at TIMESTAMPTZ   NOT NULL,
    last_modified_by VARCHAR(255)  NOT NULL,
    CONSTRAINT uk_tin_name UNIQUE (name)
);

CREATE TABLE tin_session
(
    id                BIGSERIAL PRIMARY KEY,
    workspace_id      BIGINT       NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    llm_session_id    BIGINT       NOT NULL REFERENCES llm_session (id) ON DELETE CASCADE,
    agent_id          BIGINT,
    agent_name        VARCHAR(255),
    tin_kind          VARCHAR(16)  NOT NULL,
    tin_id            BIGINT,
    tin_name          VARCHAR(255) NOT NULL,
    remote_session_id VARCHAR(255) NOT NULL,
    status            VARCHAR(32)  NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL,
    last_used_at      TIMESTAMPTZ,
    closed_at         TIMESTAMPTZ,
    CONSTRAINT ck_tin_session_kind CHECK (tin_kind IN ('LOCAL', 'REMOTE'))
);

CREATE INDEX idx_tin_session_workspace_id ON tin_session (workspace_id);
CREATE INDEX idx_tin_session_llm_session_id ON tin_session (llm_session_id, created_at);

ALTER TABLE agent ADD COLUMN tin_labels TEXT;
