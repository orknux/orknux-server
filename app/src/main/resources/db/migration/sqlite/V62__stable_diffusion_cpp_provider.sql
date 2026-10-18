-- Postgres V384: a provider of type STABLE_DIFFUSION_CPP, and edit_mask - how a
-- picture edit's mask is drawn for the provider's server, null for its type's.
--
-- SQLite cannot change a CHECK in place, so model_provider is built again with
-- the wider one and the new column, by SQLite's own procedure for a table other
-- tables point at (https://sqlite.org/lang_altertable.html#otheralter), as V35
-- did: foreign keys off, the new table renamed into place, and the keys checked
-- before they are switched back on. Outside a transaction, for the same reason
-- (V62__stable_diffusion_cpp_provider.sql.conf).

PRAGMA foreign_keys = OFF;

-- A savepoint rather than BEGIN, which Flyway's SQLite parser reads as the start of a trigger body.
SAVEPOINT stable_diffusion_cpp_provider;

CREATE TABLE model_provider_new
(
    id                           integer not null primary key autoincrement,
    workspace_id                 integer not null,
    name                         varchar(120) not null,
    endpoint                     varchar(1000) not null,
    secret                       varchar(4000),
    secret_variable_id           integer,
    type                         varchar(24) not null default 'OPENAI',
    auth_method                  varchar(16) not null default 'API_KEY',
    api_version                  varchar(32),
    deployment_name              varchar(120),
    region                       varchar(64),
    tenant_id                    varchar(120),
    client_id                    varchar(120),
    scope                        varchar(300),
    -- V225: whether the sweep is allowed to ask this one anything. The timer
    -- only; Test Connection and every call made through the provider ignore it.
    check_enabled                boolean not null default true,
    -- V293: default rate this provider's models hold themselves under, and
    -- whether a 429's Retry-After is obeyed. A model overrides. Issue #426.
    throttle_tokens_per_second   bigint,
    throttle_requests_per_second double precision,
    accept_retry_after           boolean not null default true,
    status                       varchar(16) not null default 'NOT_CONFIGURED',
    last_check_message           varchar(500),
    last_checked_at              timestamp,
    chat_api                     varchar(24),
    send_reasoning               BOOLEAN NOT NULL DEFAULT FALSE,
    offer_all_tools              BOOLEAN,
    max_tools                    INTEGER,
    edit_mask                    VARCHAR(20),
    constraint uk_model_provider_name UNIQUE (workspace_id, name),
    constraint ck_model_provider_auth CHECK (((auth_method) IN ('API_KEY', 'ENTRA_ID'))),
    -- V188: the two kinds of credential are exclusive. No foreign key to
    -- workspace_variable on either engine - that is the application's table and
    -- this is the connection module's.
    constraint ck_model_provider_credential CHECK (secret_variable_id IS NULL OR secret IS NULL),
    constraint ck_model_provider_status CHECK (((status) IN ('NOT_CONFIGURED', 'NOT_CHECKED', 'CONNECTED', 'FAILED'))),
    constraint ck_model_provider_type CHECK (((type) IN ('OPENAI', 'ANTHROPIC', 'AZURE_OPENAI', 'OLLAMA', 'SYSTEM_ONE', 'LLAMA_CPP', 'STABLE_DIFFUSION_CPP')))
);

INSERT INTO model_provider_new (id, workspace_id, name, endpoint, secret, secret_variable_id, type, auth_method, api_version, deployment_name, region, tenant_id, client_id, scope, check_enabled, throttle_tokens_per_second, throttle_requests_per_second, accept_retry_after, status, last_check_message, last_checked_at, chat_api, send_reasoning, offer_all_tools, max_tools, edit_mask)
SELECT id, workspace_id, name, endpoint, secret, secret_variable_id, type, auth_method, api_version, deployment_name, region, tenant_id, client_id, scope, check_enabled, throttle_tokens_per_second, throttle_requests_per_second, accept_retry_after, status, last_check_message, last_checked_at, chat_api, send_reasoning, offer_all_tools, max_tools, NULL FROM model_provider;

DROP TABLE model_provider;

ALTER TABLE model_provider_new RENAME TO model_provider;

CREATE INDEX idx_model_provider_workspace ON model_provider (workspace_id);

RELEASE stable_diffusion_cpp_provider;

PRAGMA foreign_keys = ON;
