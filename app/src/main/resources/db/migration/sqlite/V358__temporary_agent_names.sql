-- Postgres V678: a temporary agent's name is unique within its conversation,
-- and the workspace's agents among themselves - not every agent across the
-- workspace.
--
-- SQLite cannot drop a constraint written into the table, so agent is built
-- again without uk_agent_workspace_name, as V106 built llm_model: foreign keys
-- off, the new table renamed into place, outside a transaction
-- (V358__temporary_agent_names.sql.conf). Every column it has gained since the
-- baseline is carried: tin_labels, may_ask_for_tools, chattiness,
-- max_subagents_at_once, reasoning_effort, temporary, created_by_session_id and
-- created_by_agent_id. The two unique rules come back as partial indexes.

PRAGMA foreign_keys = OFF;

-- A savepoint rather than BEGIN, which Flyway's SQLite parser reads as the start of a trigger body.
SAVEPOINT temporary_agent_names;

CREATE TABLE agent_new
(
    id                    integer not null primary key autoincrement,
    workspace_id          integer not null,
    name                  varchar(255) not null,
    type                  varchar(16) not null,
    description           varchar(500),
    system_prompt         text,
    enabled               boolean not null default true,
    model_id              integer,
    icon                  varchar(40),
    orknux_access         boolean not null default false,
    shell_access          boolean not null default false,
    last_modified_at      timestamp not null default CURRENT_TIMESTAMP,
    last_modified_by      varchar(120) not null default '',
    memory_share          integer,
    max_rounds            integer,
    max_tools             integer,
    tin_labels            TEXT,
    may_ask_for_tools     BOOLEAN NOT NULL DEFAULT 1,
    chattiness            VARCHAR(16) NOT NULL DEFAULT 'MEDIUM',
    max_subagents_at_once INTEGER,
    reasoning_effort      VARCHAR(16),
    temporary             boolean NOT NULL DEFAULT false,
    created_by_session_id integer,
    created_by_agent_id   integer,
    constraint ck_agent_chattiness CHECK (chattiness IN ('NONE', 'LOW', 'MEDIUM', 'HIGH')),
    constraint ck_agent_type CHECK (((type) = 'LLM')),
    constraint ck_agent_memory_share CHECK (memory_share IS NULL OR (memory_share >= 1 AND memory_share <= 50)),
    constraint agent_team_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE
);

INSERT INTO agent_new (id, workspace_id, name, type, description, system_prompt, enabled, model_id, icon, orknux_access, shell_access, last_modified_at, last_modified_by, memory_share, max_rounds, max_tools, tin_labels, may_ask_for_tools, chattiness, max_subagents_at_once, reasoning_effort, temporary, created_by_session_id, created_by_agent_id)
SELECT id, workspace_id, name, type, description, system_prompt, enabled, model_id, icon, orknux_access, shell_access, last_modified_at, last_modified_by, memory_share, max_rounds, max_tools, tin_labels, may_ask_for_tools, chattiness, max_subagents_at_once, reasoning_effort, temporary, created_by_session_id, created_by_agent_id FROM agent;

DROP TABLE agent;

ALTER TABLE agent_new RENAME TO agent;

CREATE INDEX idx_agent_workspace_id ON agent (workspace_id);
CREATE INDEX idx_agent_created_by_session ON agent (created_by_session_id);
CREATE UNIQUE INDEX uk_agent_workspace_name ON agent (workspace_id, name) WHERE NOT temporary;
CREATE UNIQUE INDEX uk_agent_session_name ON agent (created_by_session_id, name) WHERE temporary;

RELEASE temporary_agent_names;

PRAGMA foreign_keys = ON;
