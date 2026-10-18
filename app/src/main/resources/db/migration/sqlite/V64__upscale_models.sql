-- Postgres V386: a provider of type STABLE_DIFFUSION_WEBUI, a model kind
-- UPSCALE, and the algorithm and scale of an upscale asked for in Design.
--
-- SQLite cannot change a CHECK in place, so model_provider and llm_model are
-- built again with the wider ones, as V62 did: foreign keys off, each new table
-- renamed into place, outside a transaction (V64__upscale_models.sql.conf).

PRAGMA foreign_keys = OFF;

-- A savepoint rather than BEGIN, which Flyway's SQLite parser reads as the start of a trigger body.
SAVEPOINT upscale_models;

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
    constraint ck_model_provider_type CHECK (((type) IN ('OPENAI', 'ANTHROPIC', 'AZURE_OPENAI', 'OLLAMA', 'SYSTEM_ONE', 'LLAMA_CPP', 'STABLE_DIFFUSION_CPP', 'STABLE_DIFFUSION_WEBUI')))
);

INSERT INTO model_provider_new (id, workspace_id, name, endpoint, secret, secret_variable_id, type, auth_method, api_version, deployment_name, region, tenant_id, client_id, scope, check_enabled, throttle_tokens_per_second, throttle_requests_per_second, accept_retry_after, status, last_check_message, last_checked_at, chat_api, send_reasoning, offer_all_tools, max_tools, edit_mask)
SELECT id, workspace_id, name, endpoint, secret, secret_variable_id, type, auth_method, api_version, deployment_name, region, tenant_id, client_id, scope, check_enabled, throttle_tokens_per_second, throttle_requests_per_second, accept_retry_after, status, last_check_message, last_checked_at, chat_api, send_reasoning, offer_all_tools, max_tools, edit_mask FROM model_provider;

DROP TABLE model_provider;

ALTER TABLE model_provider_new RENAME TO model_provider;

CREATE INDEX idx_model_provider_workspace ON model_provider (workspace_id);

CREATE TABLE llm_model_new
(
    id                           integer not null primary key autoincrement,
    provider_id                  integer not null,
    name                         varchar(120) not null,
    model_id                     varchar(200) not null,
    kind                         varchar(16) not null default 'CHAT',
    context_window               integer,
    max_output                   integer,
    parallel_tool_calls          boolean,
    temperature                  real,
    top_p                        real,
    top_k                        integer,
    min_p                        real,
    repeat_penalty               real,
    enabled                      boolean not null default true,
    token_limit                  integer,
    reset_interval               varchar(16) not null default 'MONTHLY',
    requests_per_minute          integer,
    -- V293: this model's own throttle, overriding the provider default. Null
    -- inherits; 0 turns a dimension off. A rate to stay under, not a cap that
    -- resets. Issue #426.
    throttle_tokens_per_second   bigint,
    throttle_requests_per_second double precision,
    accept_retry_after           boolean,
    input_cost_per_million       numeric(12,4),
    output_cost_per_million      numeric(12,4),
    voice                        varchar(80),
    speech_skip_empty_lines      boolean not null default false,
    image_cost_per_image         numeric(12,4),
    -- V9
    reasoning_effort             varchar(16),
    constraint uk_llm_model_name UNIQUE (provider_id, name),
    constraint ck_llm_model_kind CHECK (((kind) IN ('CHAT', 'EMBEDDING', 'COMPLETION', 'TRANSCRIPTION', 'SPEECH', 'IMAGE', 'DECISION', 'UPSCALE'))),
    constraint ck_llm_model_reset CHECK (((reset_interval) IN ('DAILY', 'WEEKLY', 'MONTHLY', 'NEVER'))),
    constraint llm_model_provider_id_fkey FOREIGN KEY (provider_id) REFERENCES model_provider(id) ON DELETE CASCADE
);

INSERT INTO llm_model_new (id, provider_id, name, model_id, kind, context_window, max_output, parallel_tool_calls, temperature, top_p, top_k, min_p, repeat_penalty, enabled, token_limit, reset_interval, requests_per_minute, throttle_tokens_per_second, throttle_requests_per_second, accept_retry_after, input_cost_per_million, output_cost_per_million, voice, speech_skip_empty_lines, image_cost_per_image, reasoning_effort)
SELECT id, provider_id, name, model_id, kind, context_window, max_output, parallel_tool_calls, temperature, top_p, top_k, min_p, repeat_penalty, enabled, token_limit, reset_interval, requests_per_minute, throttle_tokens_per_second, throttle_requests_per_second, accept_retry_after, input_cost_per_million, output_cost_per_million, voice, speech_skip_empty_lines, image_cost_per_image, reasoning_effort FROM llm_model;

DROP TABLE llm_model;

ALTER TABLE llm_model_new RENAME TO llm_model;

CREATE INDEX idx_llm_model_provider ON llm_model (provider_id);

ALTER TABLE design_image_job ADD COLUMN upscaler VARCHAR(255);
ALTER TABLE design_image_job ADD COLUMN scale DOUBLE PRECISION;

RELEASE upscale_models;

PRAGMA foreign_keys = ON;
