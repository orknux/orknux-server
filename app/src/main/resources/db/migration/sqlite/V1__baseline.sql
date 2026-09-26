-- The whole schema, in one file, for SQLite.
--
-- Postgres has ninety-eight migrations behind it and this has one. That is
-- deliberate rather than lazy. A migration is a record of how a schema changed,
-- and it is only worth replaying where somebody has a database that was built
-- by the earlier ones. Nobody has a SQLite installation older than this file, so
-- there is nothing here to replay onto - and translating the ninety-eight would
-- mean carrying the parts of that history SQLite cannot express at all: thirty
-- three constraints dropped and put back by an ALTER it has no equivalent for,
-- and a backfill in V70 written in Postgres string functions. Squashing the
-- history removes the need for any of it, because a table that is created once
-- is created correctly.
--
-- What it costs is that the two schemas can drift. Every future change has to be
-- written twice - a numbered migration under postgresql, the same change under
-- sqlite - and SqliteSchemaTest is what notices when only one of them was, with
-- SqliteCheckConstraintTest covering the CHECK constraints that schema validation
-- looks straight through.
--
-- Generated from the Postgres schema after all ninety-eight had run, so the two
-- start out saying the same thing. The differences that remain are the ones
-- SQLite forces: an identity column has to be INTEGER rather than BIGINT
-- because that is the only column SQLite will fill in by itself, TIMESTAMPTZ
-- becomes TIMESTAMP because SQLite has no zoned type, and BYTEA becomes BLOB.

CREATE TABLE agent
(
    id                           integer not null primary key autoincrement,
    workspace_id                 integer not null,
    name                         varchar(255) not null,
    type                         varchar(16) not null,
    description                  varchar(500),
    system_prompt                text,
    enabled                      boolean not null default true,
    model_id                     integer,
    icon                         varchar(40),
    orknux_access                boolean not null default false,
    shell_access                 boolean not null default false,
    -- Gone from the entity since #444, and dropped by V5 - which reads them
    -- first to decide which built-in names an agent gets, so they have to be
    -- here for a fresh installation's V5 to read and drop. See that file.
    artifact_access              boolean not null default true,
    finish_access                boolean not null default true,
    picture_link_access          boolean not null default true,
    last_modified_at             timestamp not null default CURRENT_TIMESTAMP,
    last_modified_by             varchar(120) not null default '',
    memory_share                 integer,
    -- How many rounds of tool calls this agent gets before it must answer; null
    -- is the installation's own number. See `V271__agent_max_rounds.sql`.
    max_rounds                   integer,
    -- How many tools this agent carries; null is the provider's own ceiling.
    -- See `V279__agent_tool_budget.sql`.
    max_tools                    integer,
    constraint uk_agent_workspace_name UNIQUE (workspace_id, name),
    constraint ck_agent_type CHECK (((type) = 'LLM')),
    constraint ck_agent_memory_share CHECK (memory_share IS NULL OR (memory_share >= 1 AND memory_share <= 50)),
    constraint agent_team_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE
);

CREATE TABLE agent_granted_tool
(
    agent_id                     integer not null,
    position                     integer not null,
    name                         varchar(120) not null,
    primary key (agent_id, position),
    constraint agent_granted_tool_agent_id_fkey FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE
);

CREATE TABLE agent_connection
(
    agent_id                     integer not null,
    position                     integer not null,
    connection_id                integer not null,
    primary key (agent_id, position),
    constraint agent_connection_agent_id_fkey FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE
);

CREATE TABLE agent_required_tool
(
    agent_id                     integer not null,
    position                     integer not null,
    name                         varchar(255) not null,
    primary key (agent_id, position),
    constraint agent_required_tool_agent_id_fkey FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE
);

CREATE TABLE agent_agent
(
    agent_id                     integer not null,
    position                     integer not null,
    granted_id                   integer not null,
    primary key (agent_id, position),
    constraint agent_agent_agent_id_fkey FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE
);

CREATE TABLE agent_mcp_server
(
    agent_id                     integer not null,
    position                     integer not null,
    name                         varchar(255) not null,
    primary key (agent_id, position),
    constraint agent_mcp_server_agent_id_fkey FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE
);

CREATE TABLE agent_memory_catalog
(
    agent_id                     integer not null,
    position                     integer not null,
    name                         varchar(120) not null,
    primary key (agent_id, position),
    constraint agent_memory_catalog_agent_id_fkey FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE
);

CREATE TABLE agent_skill
(
    id                           integer not null primary key autoincrement,
    workspace_id                 integer not null,
    name                         varchar(120) not null,
    skill_key                    varchar(120) not null,
    description                  varchar(500),
    content                      text not null,
    enabled                      boolean not null default true,
    last_modified_at             timestamp not null,
    last_modified_by             varchar(120) not null,
    catalog_id                   integer not null,
    constraint uk_agent_skill_name UNIQUE (workspace_id, name),
    constraint uk_agent_skill_key UNIQUE (workspace_id, skill_key COLLATE NOCASE),
    constraint agent_skill_catalog_id_fkey FOREIGN KEY (catalog_id) REFERENCES skill_catalog(id),
    constraint agent_skill_team_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE
);

CREATE TABLE agent_skill_catalog
(
    agent_id                     integer not null,
    position                     integer not null,
    name                         varchar(120) not null,
    primary key (agent_id, position),
    constraint agent_skill_catalog_agent_id_fkey FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE
);

CREATE TABLE agent_tool
(
    id                           integer not null primary key autoincrement,
    workspace_id                 integer not null,
    name                         varchar(120) not null,
    description                  varchar(4000),
    source                       text not null,
    enabled                      boolean not null default true,
    last_modified_at             timestamp not null,
    last_modified_by             varchar(120) not null,
    typescript                   text not null,
    timeout_seconds              integer,
    constraint uk_agent_tool_name UNIQUE (workspace_id, name),
    constraint agent_tool_team_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE
);

CREATE TABLE agent_tool_external
(
    tool_id                      integer not null,
    variable_id                  integer not null,
    position                     integer not null,
    primary key (tool_id, position),
    constraint agent_tool_external_tool_id_fkey FOREIGN KEY (tool_id) REFERENCES agent_tool(id) ON DELETE CASCADE,
    constraint agent_tool_external_variable_id_fkey FOREIGN KEY (variable_id) REFERENCES workspace_variable(id) ON DELETE RESTRICT
);

CREATE TABLE agent_tool_import
(
    tool_id                      integer not null,
    position                     integer not null,
    imported_id                  integer not null,
    import_name                  varchar(64) not null,
    primary key (tool_id, position),
    constraint uq_agent_tool_import_name UNIQUE (tool_id, import_name),
    constraint agent_tool_import_tool_id_fkey FOREIGN KEY (tool_id) REFERENCES agent_tool(id) ON DELETE CASCADE
);

CREATE TABLE agent_tool_library
(
    tool_id                      integer not null,
    position                     integer not null,
    imported_id                  integer not null,
    import_name                  varchar(64) not null,
    primary key (tool_id, position),
    constraint uq_agent_tool_library_name UNIQUE (tool_id, import_name),
    constraint agent_tool_library_tool_id_fkey FOREIGN KEY (tool_id) REFERENCES agent_tool(id) ON DELETE CASCADE
);

CREATE TABLE agent_tool_param
(
    tool_id                      integer not null,
    position                     integer not null,
    name                         varchar(64) not null,
    type                         varchar(16) not null,
    object_id                    integer,
    primary key (tool_id, position),
    constraint ck_agent_tool_param_object CHECK (((((type) = 'OBJECT') AND (object_id IS NOT NULL)) OR (((type) != 'OBJECT') AND (object_id IS NULL)))),
    constraint ck_agent_tool_param_type CHECK (((type) IN ('STRING', 'NUMBER', 'BOOLEAN', 'OBJECT', 'MAP', 'ARRAY', 'CONNECTION'))),
    constraint agent_tool_param_tool_id_fkey FOREIGN KEY (tool_id) REFERENCES agent_tool(id) ON DELETE CASCADE
);

CREATE TABLE app_user
(
    id                           integer not null primary key autoincrement,
    username                     varchar(120) not null,
    display_name                 varchar(200) not null,
    type                         varchar(16) not null,
    created_at                   timestamp not null default CURRENT_TIMESTAMP,
    last_modified_at             timestamp not null default CURRENT_TIMESTAMP,
    last_modified_by             varchar(120) not null default 'system',
    password_hash                varchar(100),
    email                        varchar(320),
    email_chosen                 boolean not null default false,
    email_notifications          boolean not null default true,
    chat_cost_shown              boolean not null default false,
    language                     varchar(16)
);

CREATE TABLE app_user_role
(
    user_id                      integer not null,
    role_id                      integer not null,
    primary key (user_id, role_id),
    constraint app_user_role_role_id_fkey FOREIGN KEY (role_id) REFERENCES security_role(id) ON DELETE CASCADE,
    constraint app_user_role_user_id_fkey FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE
);

CREATE TABLE app_user_token
(
    id                           integer not null primary key autoincrement,
    user_id                      integer not null,
    name                         varchar(120) not null,
    token_hash                   varchar(64) not null,
    created_at                   timestamp not null default CURRENT_TIMESTAMP,
    last_used_at                 timestamp,
    constraint app_user_token_user_id_fkey FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE
);

CREATE TABLE chat_answer_take
(
    id                           integer not null primary key autoincrement,
    chat_session_id              integer not null,
    message_index                integer not null,
    content                      text not null,
    taken_at                     timestamp not null default CURRENT_TIMESTAMP,
    constraint chat_answer_take_chat_session_id_fkey FOREIGN KEY (chat_session_id) REFERENCES chat_session(id) ON DELETE CASCADE
);

CREATE TABLE chat_message_thinking
(
    id                           integer not null primary key autoincrement,
    chat_session_id              integer not null,
    message_index                integer not null,
    content                      text not null,
    millis                       integer not null default 0,
    thought_at                   timestamp not null default CURRENT_TIMESTAMP,
    constraint chat_message_thinking_at_uq UNIQUE (chat_session_id, message_index),
    constraint chat_message_thinking_chat_session_id_fkey FOREIGN KEY (chat_session_id) REFERENCES chat_session(id) ON DELETE CASCADE
);

CREATE TABLE chat_attachment
(
    id                           integer not null primary key autoincrement,
    workspace_id                 integer not null,
    chat_session_id              integer,
    filename                     varchar(255) not null,
    content_type                 varchar(120) not null,
    size_bytes                   integer not null,
    location                     varchar(1000) not null,
    uploaded_at                  timestamp not null default CURRENT_TIMESTAMP,
    uploaded_by                  varchar(120) not null default '',
    constraint chat_attachment_chat_session_id_fkey FOREIGN KEY (chat_session_id) REFERENCES chat_session(id) ON DELETE CASCADE,
    constraint chat_attachment_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE
);

CREATE TABLE chat_session
(
    id                           integer not null primary key autoincrement,
    workspace_id                 integer not null,
    conversation_id              varchar(36) not null,
    title                        varchar(200) not null,
    user_id                      varchar(120) not null,
    model_id                     integer,
    pinned                       boolean not null default false,
    created_at                   timestamp not null,
    last_message_at              timestamp,
    agent_id                     integer,
    llm_session_id               integer,
    spent_input_tokens           integer not null default 0,
    spent_output_tokens          integer not null default 0,
    spent_pictures               integer not null default 0,
    constraint uk_chat_session_conversation UNIQUE (conversation_id),
    constraint chat_session_agent_id_fkey FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE SET NULL,
    constraint chat_session_llm_session_id_fkey FOREIGN KEY (llm_session_id) REFERENCES llm_session(id) ON DELETE SET NULL,
    constraint chat_session_model_id_fkey FOREIGN KEY (model_id) REFERENCES llm_model(id) ON DELETE SET NULL,
    constraint chat_session_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE
);

CREATE TABLE component_revision
(
    id                           integer not null primary key autoincrement,
    workspace_id                 integer not null,
    kind                         varchar(16) not null,
    component_id                 integer not null,
    name                         varchar(120) not null,
    saved_at                     timestamp not null,
    saved_by                     varchar(120) not null,
    recorded_at                  timestamp not null default CURRENT_TIMESTAMP,
    snapshot                     text not null,
    constraint component_revision_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE
);

CREATE TABLE component_template
(
    id                           integer not null primary key autoincrement,
    name                         varchar(120) not null,
    description                  varchar(1000),
    envelope                     text not null,
    created_at                   timestamp not null default CURRENT_TIMESTAMP,
    created_by                   varchar(255) not null,
    last_modified_at             timestamp not null default CURRENT_TIMESTAMP,
    last_modified_by             varchar(255) not null
);

CREATE TABLE connection
(
    id                           integer not null primary key autoincrement,
    name                         varchar(255) not null,
    type                         varchar(24) not null,
    url                          varchar(1000) not null,
    constraint uk_connection_name UNIQUE (name),
    constraint ck_connection_type CHECK (((type) IN ('SLACK', 'SMTP', 'HTTP')))
);

CREATE TABLE execution_log
(
    id                           integer not null primary key autoincrement,
    execution_id                 integer not null,
    node_key                     varchar(64),
    logged_at                    timestamp not null,
    level                        varchar(16) not null,
    message                      varchar(2000) not null,
    sequence_no                  integer not null,
    constraint ck_execution_log_level CHECK (((level) IN ('INFO', 'SUCCESS', 'ERROR'))),
    constraint execution_log_execution_id_fkey FOREIGN KEY (execution_id) REFERENCES workflow_execution(id) ON DELETE CASCADE
);

CREATE TABLE execution_step
(
    id                           integer not null primary key autoincrement,
    execution_id                 integer not null,
    node_key                     varchar(64) not null,
    kind                         varchar(16) not null,
    name                         varchar(255) not null,
    description                  varchar(500),
    status                       varchar(16) not null,
    position_x                   float not null,
    position_y                   float not null,
    step_order                   integer not null,
    started_at                   timestamp,
    finished_at                  timestamp,
    input                        text,
    output                       text,
    error                        varchar(1000),
    action_id                    integer,
    condition_id                 integer,
    wait_until                   timestamp,
    mappings                     text,
    agent_id                     integer,
    image_model_id               integer,
    image_size                   varchar(16),
    image_quality                varchar(16),
    image_style                  varchar(16),
    output_object_id             integer,
    output_name                  varchar(60),
    branch                       varchar(8),
    carried_over                 boolean not null default false,
    retry_attempts               integer,
    retry_backoff_seconds        integer,
    retry_multiplier             float,
    retry_max_wait_seconds       integer,
    retry_jitter                 float,
    retry_budget_seconds         integer,
    retry_deadline               timestamp,
    attempts                     integer not null default 0,
    agent_sleeps                 integer not null default 0,
    agent_sleep_note             text,
    session_id                   integer,
    enabled                      boolean not null default true,
    constraint uk_execution_step UNIQUE (execution_id, node_key),
    constraint ck_execution_step_branch CHECK (((branch IS NULL) OR ((branch) IN ('YES', 'NO', 'FAILURE')))),
    constraint ck_execution_step_kind CHECK (((kind) IN ('TRIGGER', 'AGENT', 'ACTION', 'CONDITION', 'OBJECT', 'IMAGE'))),
    constraint ck_execution_step_status CHECK (((status) IN ('PENDING', 'RUNNING', 'WAITING', 'COMPLETED', 'FAILED', 'SKIPPED'))),
    constraint execution_step_execution_id_fkey FOREIGN KEY (execution_id) REFERENCES workflow_execution(id) ON DELETE CASCADE
);

CREATE TABLE installation_setting
(
    name                         varchar(120) not null,
    value                        varchar(500) not null,
    last_modified_at             timestamp not null default CURRENT_TIMESTAMP,
    last_modified_by             varchar(120) not null default '',
    primary key (name)
);

CREATE TABLE issue_news
(
    id                           integer not null primary key autoincrement,
    workspace_id                 integer not null,
    issue_id                     integer,
    issue_number                 integer,
    issue_title                  varchar(200),
    task_id                      integer,
    task_title                   varchar(200),
    kind                         varchar(16) not null,
    actor                        varchar(120) not null,
    says                         text,
    -- Which comment `says` is a copy of, so a comment removed from the tracker
    -- can be removed from the bells it was announced to. See V208.
    comment_id                   integer,
    audience_kind                varchar(16) not null,
    audience_id                  varchar(120),
    audience_name                varchar(120) not null,
    at                           timestamp not null default CURRENT_TIMESTAMP,
    constraint issue_news_issue_id_fkey FOREIGN KEY (issue_id) REFERENCES workspace_issue(id) ON DELETE CASCADE,
    constraint issue_news_task_id_fkey FOREIGN KEY (task_id) REFERENCES task(id) ON DELETE CASCADE,
    constraint issue_news_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE
);

CREATE TABLE issue_news_read
(
    workspace_id                 integer not null,
    reader_kind                  varchar(16) not null,
    reader_name                  varchar(120) not null,
    last_id                      integer not null,
    at                           timestamp not null default CURRENT_TIMESTAMP,
    primary key (workspace_id, reader_kind, reader_name),
    constraint issue_news_read_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE
);

CREATE TABLE llm_model
(
    id                           integer not null primary key autoincrement,
    provider_id                  integer not null,
    name                         varchar(120) not null,
    model_id                     varchar(200) not null,
    kind                         varchar(16) not null default 'CHAT',
    context_window               integer,
    max_output                   integer,
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
    constraint uk_llm_model_name UNIQUE (provider_id, name),
    constraint ck_llm_model_kind CHECK (((kind) IN ('CHAT', 'EMBEDDING', 'COMPLETION', 'TRANSCRIPTION', 'SPEECH', 'IMAGE'))),
    constraint ck_llm_model_reset CHECK (((reset_interval) IN ('DAILY', 'WEEKLY', 'MONTHLY', 'NEVER'))),
    constraint llm_model_provider_id_fkey FOREIGN KEY (provider_id) REFERENCES model_provider(id) ON DELETE CASCADE
);

CREATE TABLE llm_session
(
    id                           integer not null primary key autoincrement,
    workspace_id                 integer not null,
    session_key                  varchar(300) not null,
    key_prefix                   varchar(120),
    created_at                   timestamp not null default CURRENT_TIMESTAMP,
    last_event_at                timestamp,
    found_tools                  text,
    parent_session_id            integer,
    title                        varchar(200),
    agent_details                text,
    constraint llm_session_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE,
    constraint llm_session_parent_session_id_fkey FOREIGN KEY (parent_session_id) REFERENCES llm_session(id) ON DELETE SET NULL
);

CREATE INDEX llm_session_parent_idx ON llm_session (parent_session_id, created_at, id);

CREATE TABLE llm_session_note
(
    id                           integer not null primary key autoincrement,
    session_id                   integer not null,
    note                         text not null,
    written_by                   varchar(200) not null,
    written_at                   timestamp not null default CURRENT_TIMESTAMP,
    constraint llm_session_note_session_id_fkey FOREIGN KEY (session_id) REFERENCES llm_session(id) ON DELETE CASCADE
);

CREATE INDEX llm_session_note_session_idx ON llm_session_note (session_id, written_at, id);

CREATE TABLE session_scratchpad
(
    id                           integer not null primary key autoincrement,
    session_id                   integer not null,
    name                         varchar(200) not null,
    description                  varchar(500),
    content                      text not null default '',
    shared                       boolean not null default false,
    created_at                   timestamp not null default CURRENT_TIMESTAMP,
    updated_at                   timestamp not null default CURRENT_TIMESTAMP,
    constraint uk_session_scratchpad UNIQUE (session_id, name),
    constraint session_scratchpad_session_id_fkey FOREIGN KEY (session_id) REFERENCES llm_session(id) ON DELETE CASCADE
);

CREATE INDEX idx_session_scratchpad_session ON session_scratchpad (session_id);

CREATE TABLE llm_session_store
(
    session_id                   integer not null,
    name                         varchar(200) not null,
    value                        text not null,
    primary key (session_id, name),
    constraint llm_session_store_session_id_fkey FOREIGN KEY (session_id) REFERENCES llm_session(id) ON DELETE CASCADE
);

CREATE TABLE llm_session_event
(
    id                           integer not null primary key autoincrement,
    session_id                   integer not null,
    kind                         varchar(16) not null,
    actor                        varchar(200) not null,
    content                      text,
    result                       text,
    -- How long a THINKING line's reasoning went on for, and null while it is
    -- still going. See V209 for why one column says both.
    millis                       integer,
    at                           timestamp not null default CURRENT_TIMESTAMP,
    constraint llm_session_event_session_id_fkey FOREIGN KEY (session_id) REFERENCES llm_session(id) ON DELETE CASCADE
);

CREATE TABLE trusted_certificate
(
    id               integer not null primary key autoincrement,
    name             varchar(120) not null,
    pem              text not null,
    subject          varchar(500) not null,
    expires_at       timestamp,
    added_at         timestamp not null default current_timestamp,
    added_by         varchar(120) not null
);

CREATE TABLE mcp_server
(
    id                           integer not null primary key autoincrement,
    workspace_id                 integer not null,
    name                         varchar(255) not null,
    address                      varchar(1000) not null,
    auth_type                    varchar(16) not null default 'NONE',
    secret                       varchar(4000),
    secret_variable_id           integer,
    -- The last check's result, so the list shows reachability without a press. #329
    last_checked_at              timestamp,
    reachable                    boolean,
    check_detail                 varchar(1000),
    tool_count                   integer,
    constraint uk_mcp_server_workspace_name UNIQUE (workspace_id, name),
    constraint ck_mcp_server_auth CHECK (((auth_type) IN ('NONE', 'API_KEY', 'BEARER_TOKEN', 'BASIC'))),
    constraint ck_mcp_server_secret CHECK (secret_variable_id IS NULL OR secret IS NULL)
);

CREATE TABLE mcp_server_header
(
    mcp_server_id                integer not null,
    position                     integer not null,
    name                         varchar(255) not null,
    value                        varchar(1000) not null,
    primary key (mcp_server_id, position),
    constraint mcp_server_header_mcp_server_id_fkey FOREIGN KEY (mcp_server_id) REFERENCES mcp_server(id) ON DELETE CASCADE
);

CREATE TABLE memory
(
    id                           integer not null primary key autoincrement,
    catalog_id                   integer not null,
    title                        varchar(200) not null,
    content                      text not null,
    created_at                   timestamp not null,
    created_by                   varchar(120) not null,
    last_modified_at             timestamp not null,
    last_modified_by             varchar(120) not null,
    constraint memory_catalog_id_fkey FOREIGN KEY (catalog_id) REFERENCES memory_catalog(id) ON DELETE CASCADE
);

CREATE TABLE memory_catalog
(
    id                           integer not null primary key autoincrement,
    workspace_id                 integer not null,
    name                         varchar(120) not null,
    created_at                   timestamp not null,
    created_by                   varchar(120) not null,
    is_default                   boolean not null default false,
    constraint uk_memory_catalog_name UNIQUE (workspace_id, name)
);

CREATE TABLE model_provider
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
    constraint uk_model_provider_name UNIQUE (workspace_id, name),
    constraint ck_model_provider_auth CHECK (((auth_method) IN ('API_KEY', 'ENTRA_ID'))),
    -- V188: the two kinds of credential are exclusive. No foreign key to
    -- workspace_variable on either engine - that is the application's table and
    -- this is the connection module's.
    constraint ck_model_provider_credential CHECK (secret_variable_id IS NULL OR secret IS NULL),
    constraint ck_model_provider_status CHECK (((status) IN ('NOT_CONFIGURED', 'NOT_CHECKED', 'CONNECTED', 'FAILED'))),
    constraint ck_model_provider_type CHECK (((type) IN ('OPENAI', 'ANTHROPIC', 'AZURE_OPENAI', 'OLLAMA')))
);

CREATE TABLE model_usage_day
(
    id                           integer not null primary key autoincrement,
    model_id                     integer not null,
    day                          date not null,
    requests                     integer not null default 0,
    input_tokens                 integer not null default 0,
    output_tokens                integer not null default 0,
    latency_millis_total         integer not null default 0,
    constraint uk_model_usage_day UNIQUE (model_id, day),
    constraint model_usage_day_model_id_fkey FOREIGN KEY (model_id) REFERENCES llm_model(id) ON DELETE CASCADE
);

CREATE TABLE object_property
(
    object_id                    integer not null,
    position                     integer not null,
    name                         varchar(64) not null,
    kind                         varchar(16) not null,
    ref_object_id                integer,
    element_kind                 varchar(16),
    description                  varchar(500),
    primary key (object_id, position),
    constraint object_property_object_id_fkey FOREIGN KEY (object_id) REFERENCES workflow_object(id) ON DELETE CASCADE,
    -- SET NULL rather than nothing: a plugin's shapes go by cascade when it is
    -- unloaded, and ones that point at each other would otherwise delete in an
    -- order the constraint blocks. Null reads as "points at nothing", which is
    -- what happened -- where taking the field away would edit somebody's shape.
    constraint object_property_ref_object_id_fkey FOREIGN KEY (ref_object_id) REFERENCES workflow_object(id) ON DELETE SET NULL
);

CREATE TABLE password_reset
(
    id                           integer not null primary key autoincrement,
    user_id                      integer not null,
    token_hash                   varchar(64) not null,
    created_at                   timestamp not null default CURRENT_TIMESTAMP,
    expires_at                   timestamp not null,
    used_at                      timestamp,
    constraint password_reset_user_id_fkey FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE
);

CREATE TABLE plugin
(
    id                           integer not null primary key autoincrement,
    name                         varchar(200) not null,
    filename                     varchar(255) not null,
    source                       text not null,
    size_bytes                   integer not null,
    api_version                  integer not null,
    sha256                       varchar(64) not null,
    uploaded_at                  timestamp not null default CURRENT_TIMESTAMP,
    uploaded_by                  varchar(120) not null default '',
    declared_functions           text not null default '[]',
    declared_tools               text not null default '[]',
    plugin_key                   varchar(32) not null,
    typescript                   text,
    declared_parameters          text not null default '[]',
    declared_permissions         text not null default '[]',
    accepted_permissions         text not null default '[]',
    declared_capabilities        text not null default '[]',
    -- V294: the connection kinds it declares, for a workspace to hold hosts of. Issue #363.
    declared_connection_types    text not null default '[]',
    -- V299: the workflow actions it declares, for an Action node to be pointed at. Issue #438.
    declared_actions             text not null default '[]',
    accepted_capabilities        text not null default '[]',
    permissions_accepted_at      timestamp,
    permissions_accepted_by      varchar(120),
    enabled                      boolean not null default true,
    marketplace_key              varchar(64),
    marketplace_version          varchar(32),
    icon                         text,
    summary                      text,
    author                       varchar(200),
    version                      varchar(32),
    declared_skills              text not null default '[]',
    declared_objects             text not null default '[]',
    icon_dark                    text,
    declared_types               text not null default '[]'
);

CREATE TABLE plugin_library
(
    id                           integer not null primary key autoincrement,
    plugin_id                    integer not null,
    position                     integer not null,
    path                         varchar(200) not null,
    source                       text not null,
    constraint uk_plugin_library_path UNIQUE (plugin_id, path),
    constraint plugin_library_plugin_id_fkey FOREIGN KEY (plugin_id) REFERENCES plugin(id) ON DELETE CASCADE
);

CREATE TABLE plugin_parameter
(
    id                           integer not null primary key autoincrement,
    plugin_id                    integer not null,
    workspace_id                 integer not null,
    name                         varchar(64) not null,
    literal_value                text,
    secret_value                 varchar(4096),
    variable_id                  integer,
    last_modified_at             timestamp not null default CURRENT_TIMESTAMP,
    last_modified_by             varchar(120) not null default '',
    constraint plugin_parameter_one_source CHECK (((CASE WHEN literal_value IS NULL THEN 0 ELSE 1 END) + (CASE WHEN secret_value IS NULL THEN 0 ELSE 1 END) + (CASE WHEN variable_id IS NULL THEN 0 ELSE 1 END)) <= 1),
    constraint plugin_parameter_plugin_id_fkey FOREIGN KEY (plugin_id) REFERENCES plugin(id) ON DELETE CASCADE,
    constraint plugin_parameter_variable_id_fkey FOREIGN KEY (variable_id) REFERENCES workspace_variable(id) ON DELETE SET NULL,
    constraint plugin_parameter_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE
);

CREATE TABLE proxy_rule
(
    id                           integer not null primary key autoincrement,
    name                         varchar(120) not null,
    pattern                      varchar(1000) not null,
    proxy_host                   varchar(255) not null,
    proxy_port                   integer not null,
    username                     varchar(255),
    password                     varchar(4000),
    enabled                      boolean not null default true,
    position                     integer not null default 0,
    created_at                   timestamp not null default CURRENT_TIMESTAMP,
    last_modified_at             timestamp not null default CURRENT_TIMESTAMP
);

CREATE TABLE scheduled_tasks
(
    task_name                    text not null,
    task_instance                text not null,
    task_data                    blob,
    execution_time               timestamp not null,
    picked                       boolean not null,
    picked_by                    text,
    last_success                 timestamp,
    last_failure                 timestamp,
    consecutive_failures         integer,
    last_heartbeat               timestamp,
    version                      integer not null,
    priority                     integer,
    primary key (task_name, task_instance)
);

CREATE TABLE script_library
(
    id                           integer not null primary key autoincrement,
    library_key                  varchar(64) not null,
    name                         varchar(200) not null,
    filename                     varchar(255) not null,
    source                       text not null,
    typescript                   text,
    size_bytes                   integer not null,
    sha256                       varchar(64) not null,
    declared_members             text not null default '[]',
    callable                     boolean not null default 0,
    uploaded_at                  timestamp not null default CURRENT_TIMESTAMP,
    uploaded_by                  varchar(120) not null default '',
    origin                       varchar(16) not null default 'UPLOAD',
    origin_package               varchar(214),
    origin_version               varchar(64),
    origin_url                   varchar(500),
    origin_integrity             varchar(160),
    origin_entry                 varchar(255),
    -- Which spelling the stored file is: ESM, or CommonJS wrapped on the way into
    -- the sandbox. A statement about how the text is run, never about where it
    -- came from - the file is stored exactly as it arrived so that sha256 and
    -- origin_integrity stay claims anybody holding the same package can check.
    source_format                varchar(16) not null default 'ESM',
    -- What went into it, when it was made out of more than one file. Null for
    -- every library that is one file, which is the ordinary one.
    bundled_from                 text,
    constraint uq_script_library_key UNIQUE (library_key),
    constraint ck_script_library_origin CHECK ((origin) IN ('UPLOAD', 'REGISTRY')),
    constraint ck_script_library_source_format CHECK ((source_format) IN ('ESM', 'COMMONJS'))
);

CREATE TABLE security_role
(
    id                           integer not null primary key autoincrement,
    name                         varchar(120) not null,
    description                  varchar(500),
    builtin                      boolean not null default false,
    created_at                   timestamp not null default CURRENT_TIMESTAMP,
    last_modified_at             timestamp not null default CURRENT_TIMESTAMP,
    last_modified_by             varchar(120) not null default 'system'
);

CREATE TABLE security_role_match
(
    role_id                      integer not null,
    value                        varchar(500) not null,
    PRIMARY KEY (role_id, value),
    constraint security_role_match_role_id_fkey FOREIGN KEY (role_id) REFERENCES security_role(id) ON DELETE CASCADE
);

CREATE TABLE security_role_scope
(
    role_id                      integer not null,
    scope                        varchar(16) not null,
    primary key (role_id, scope),
    constraint ck_security_role_scope CHECK (((scope) IN ('ADMIN', 'USER'))),
    constraint security_role_scope_role_id_fkey FOREIGN KEY (role_id) REFERENCES security_role(id) ON DELETE CASCADE
);

CREATE TABLE shell
(
    id                           integer not null primary key autoincrement,
    kind                         varchar(10) not null default 'SSH',
    mcp_server_id                integer,
    name                         varchar(120) not null,
    host                         varchar(255) not null,
    port                         integer not null default 22,
    username                     varchar(255),
    private_key                  text,
    key_passphrase               varchar(4000),
    enabled                      boolean not null default true,
    host_key                     varchar(500),
    -- Null means "whatever this installation says", which is ShellProperties.
    -- See postgresql/V215__shell_limits.sql for why null rather than a copy of
    -- the default.
    command_timeout_seconds      integer,
    max_output_bytes             integer,
    status                       varchar(20) not null default 'NOT_CHECKED',
    last_check_message           varchar(500),
    last_checked_at              timestamp,
    created_at                   timestamp not null default CURRENT_TIMESTAMP,
    last_modified_at             timestamp not null default CURRENT_TIMESTAMP
);

CREATE TABLE shell_session
(
    id                           varchar(64) not null,
    shell_id                     integer not null,
    agent_id                     integer,
    agent_name                   varchar(120) not null,
    workspace_id                 integer,
    directory                    varchar(500) not null,
    mcp_session                  varchar(200),
    operating_system             varchar(200),
    state                        varchar(16) not null default 'OPEN',
    opened_at                    timestamp not null default CURRENT_TIMESTAMP,
    last_used_at                 timestamp not null default CURRENT_TIMESTAMP,
    closed_at                    timestamp,
    command_count                integer not null default 0,
    primary key (id),
    constraint shell_session_shell_id_fkey FOREIGN KEY (shell_id) REFERENCES shell(id) ON DELETE CASCADE
);

CREATE TABLE skill_catalog
(
    id                           integer not null primary key autoincrement,
    workspace_id                 integer not null,
    name                         varchar(120) not null,
    created_at                   timestamp not null,
    created_by                   varchar(120) not null,
    constraint uk_skill_catalog_name UNIQUE (workspace_id, name),
    constraint skill_catalog_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE
);

CREATE TABLE spring_ai_chat_memory
(
    conversation_id              varchar(36) not null,
    content                      text not null,
    type                         varchar(10) not null,
    "timestamp"                  timestamp not null,
    sequence_id                  integer not null,
    constraint spring_ai_chat_memory_type_check CHECK (((type) IN ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL')))
);

CREATE TABLE spring_session
(
    primary_id                   char(36) not null,
    session_id                   char(36) not null,
    creation_time                integer not null,
    last_access_time             integer not null,
    max_inactive_interval        integer not null,
    expiry_time                  integer not null,
    principal_name               varchar(100),
    primary key (primary_id)
);

CREATE TABLE spring_session_attributes
(
    session_primary_id           char(36) not null,
    attribute_name               varchar(200) not null,
    attribute_bytes              blob not null,
    primary key (session_primary_id, attribute_name),
    constraint spring_session_attributes_fk FOREIGN KEY (session_primary_id) REFERENCES spring_session(primary_id) ON DELETE CASCADE
);

CREATE TABLE task
(
    id                           integer not null primary key autoincrement,
    workspace_id                 integer not null,
    title                        varchar(200) not null,
    prompt                       text not null,
    agent_id                     integer,
    model_id                     integer,
    status                       varchar(16) not null,
    session_id                   integer,
    issue_id                     integer,
    created_by                   varchar(120) not null,
    created_at                   timestamp not null default CURRENT_TIMESTAMP,
    started_at                   timestamp,
    finished_at                  timestamp,
    turns_spent                  integer not null default 0,
    worked_seconds               integer not null default 0,
    turns_allowed                integer not null,
    seconds_allowed              integer not null,
    waiting_until                timestamp,
    outcome                      text,
    ended_because                varchar(200),
    constraint task_agent_id_fkey FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE SET NULL,
    constraint task_issue_id_fkey FOREIGN KEY (issue_id) REFERENCES workspace_issue(id) ON DELETE SET NULL,
    constraint task_model_id_fkey FOREIGN KEY (model_id) REFERENCES llm_model(id) ON DELETE SET NULL,
    constraint task_session_id_fkey FOREIGN KEY (session_id) REFERENCES llm_session(id) ON DELETE SET NULL,
    constraint task_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE
);

CREATE TABLE task_grant
(
    id                           integer not null primary key autoincrement,
    task_id                      integer not null,
    request_id                   integer,
    capability                   varchar(20) not null,
    subject                      varchar(200),
    granted_by                   varchar(120) not null,
    granted_at                   timestamp not null default CURRENT_TIMESTAMP,
    constraint task_grant_request_id_fkey FOREIGN KEY (request_id) REFERENCES task_request(id) ON DELETE SET NULL,
    constraint task_grant_task_id_fkey FOREIGN KEY (task_id) REFERENCES task(id) ON DELETE CASCADE
);

CREATE TABLE task_message
(
    id                           integer not null primary key autoincrement,
    task_id                      integer not null,
    said_by                      varchar(120) not null,
    body                         text not null,
    sent_at                      timestamp not null default CURRENT_TIMESTAMP,
    delivered_at                 timestamp,
    constraint task_message_task_id_fkey FOREIGN KEY (task_id) REFERENCES task(id) ON DELETE CASCADE
);

CREATE TABLE task_picture
(
    id                           integer not null primary key autoincrement,
    task_id                      integer not null,
    workspace_id                 integer not null,
    prompt                       text not null,
    filename                     varchar(255) not null,
    content_type                 varchar(120) not null,
    size_bytes                   integer not null,
    location                     varchar(1000) not null,
    drawn_at                     timestamp not null default CURRENT_TIMESTAMP,
    constraint task_picture_task_id_fkey FOREIGN KEY (task_id) REFERENCES task(id) ON DELETE CASCADE,
    constraint task_picture_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE
);

CREATE TABLE execution_picture
(
    id                           integer not null primary key autoincrement,
    execution_id                 integer not null,
    node_key                     varchar(64) not null,
    workspace_id                 integer not null,
    prompt                       text not null,
    filename                     varchar(255) not null,
    content_type                 varchar(120) not null,
    size_bytes                   integer not null,
    location                     varchar(1000) not null,
    drawn_at                     timestamp not null default CURRENT_TIMESTAMP,
    constraint execution_picture_execution_id_fkey FOREIGN KEY (execution_id) REFERENCES workflow_execution(id) ON DELETE CASCADE,
    constraint execution_picture_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE
);

CREATE INDEX execution_picture_execution_idx ON execution_picture (execution_id, drawn_at, id);

CREATE TABLE execution_speech
(
    id                           integer not null primary key autoincrement,
    execution_id                 integer not null,
    node_key                     varchar(64) not null,
    workspace_id                 integer not null,
    said                         text not null,
    filename                     varchar(255) not null,
    content_type                 varchar(120) not null,
    size_bytes                   integer not null,
    location                     varchar(1000) not null,
    spoken_at                    timestamp not null default CURRENT_TIMESTAMP,
    constraint execution_speech_execution_id_fkey FOREIGN KEY (execution_id) REFERENCES workflow_execution(id) ON DELETE CASCADE,
    constraint execution_speech_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE
);

CREATE INDEX execution_speech_execution_idx ON execution_speech (execution_id, spoken_at, id);

CREATE TABLE task_request
(
    id                           integer not null primary key autoincrement,
    task_id                      integer not null,
    kind                         varchar(16) not null,
    capability                   varchar(20),
    subject                      varchar(200),
    asks                         text not null,
    asked_at                     timestamp not null default CURRENT_TIMESTAMP,
    decision                     varchar(16),
    answer                       text,
    decided_by                   varchar(120),
    decided_at                   timestamp,
    constraint task_request_task_id_fkey FOREIGN KEY (task_id) REFERENCES task(id) ON DELETE CASCADE
);

CREATE TABLE trigger_firing
(
    id                           integer not null primary key autoincrement,
    trigger_id                   integer not null,
    workspace_id                 integer not null,
    at                           timestamp not null,
    outcome                      varchar(32) not null,
    detail                       text,
    runs_started                 integer not null default 0,
    constraint trigger_firing_trigger_id_fkey FOREIGN KEY (trigger_id) REFERENCES workflow_trigger(id) ON DELETE CASCADE
);

CREATE TABLE variable_catalog
(
    id                           integer not null primary key autoincrement,
    workspace_id                 integer not null,
    name                         varchar(120) not null,
    created_at                   timestamp not null default CURRENT_TIMESTAMP,
    created_by                   varchar(120) not null default '',
    constraint uk_variable_catalog_name UNIQUE (workspace_id, name),
    constraint variable_vault_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE
);

CREATE TABLE workflow
(
    id                           integer not null primary key autoincrement,
    name                         varchar(255) not null,
    description                  varchar(500),
    status                       varchar(16) not null default 'DRAFT',
    -- A name is unique within a workspace, checked in the application against
    -- the assignments; there is no installation-wide unique here. Issue #341.
    constraint ck_workflow_status CHECK (((status) IN ('DRAFT', 'PUBLISHED')))
);

CREATE TABLE workflow_action
(
    id                           integer not null primary key autoincrement,
    workspace_id                 integer not null,
    -- Set means the row is that workflow's own: not in the workspace's
    -- list, pointed at by nothing else, and gone when the workflow is.
    workflow_id                  integer,
    name                         varchar(120) not null,
    type                         varchar(16) not null,
    subtype                      varchar(24) not null,
    connection_id                integer,
    connection_action            varchar(32),
    content                      text,
    target_name                  varchar(120),
    url                          varchar(1000),
    method                       varchar(8),
    headers                      text,
    function_id                  integer,
    condition_expression         varchar(500),
    timeout_seconds              integer,
    retry_interval_seconds       integer,
    duration_seconds             integer,
    condition_id                 integer,
    icon                         varchar(40),
    email_to                     varchar(1000),
    email_cc                     varchar(1000),
    email_subject                varchar(500),
    email_reply_to               varchar(320),
    speech_text                  text,
    speech_voice                 varchar(80),
    speech_model_id              integer,
    -- V299: which plugin's action a PLUGIN_ACTION runs, and what the plugin calls it. Issue #438.
    plugin_key                   varchar(64),
    plugin_action                varchar(64),
    constraint ck_workflow_action_shape CHECK ((workflow_id IS NOT NULL) OR (((((type) = 'EXECUTE') AND ((subtype) = 'OUTGOING_CONNECTION') AND (connection_id IS NOT NULL)) OR (((type) = 'EXECUTE') AND ((subtype) = 'SEND_EMAIL') AND (connection_id IS NOT NULL)) OR (((type) = 'EXECUTE') AND ((subtype) = 'HTTP_REQUEST') AND (url IS NOT NULL)) OR (((type) = 'EXECUTE') AND ((subtype) = 'FUNCTION') AND (function_id IS NOT NULL)) OR (((type) = 'EXECUTE') AND ((subtype) = 'PLUGIN_ACTION') AND (plugin_key IS NOT NULL) AND (plugin_action IS NOT NULL)) OR (((type) = 'EXECUTE') AND ((subtype) = 'SPEAK') AND (speech_text IS NOT NULL)) OR (((type) = 'WAIT') AND ((subtype) = 'INLINE_CONDITION') AND (condition_expression IS NOT NULL)) OR (((type) = 'WAIT') AND ((subtype) = 'CONDITION') AND (condition_id IS NOT NULL)) OR (((type) = 'WAIT') AND ((subtype) = 'TIME') AND (duration_seconds IS NOT NULL))))),
    constraint ck_workflow_action_subtype CHECK (((subtype) IN ('OUTGOING_CONNECTION', 'SEND_EMAIL', 'HTTP_REQUEST', 'FUNCTION', 'PLUGIN_ACTION', 'INLINE_CONDITION', 'CONDITION', 'TIME', 'SPEAK'))),
    constraint ck_workflow_action_type CHECK (((type) IN ('EXECUTE', 'WAIT'))),
    constraint workflow_action_condition_id_fkey FOREIGN KEY (condition_id) REFERENCES workflow_condition(id),
    constraint workflow_action_function_id_fkey FOREIGN KEY (function_id) REFERENCES workflow_function(id),
    constraint workflow_action_team_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE
);

CREATE TABLE workflow_action_mapping
(
    action_id                    integer not null,
    position                     integer not null,
    argument                     varchar(64) not null,
    expression                   varchar(500) not null,
    primary key (action_id, position),
    constraint workflow_action_mapping_action_id_fkey FOREIGN KEY (action_id) REFERENCES workflow_action(id) ON DELETE CASCADE
);

CREATE TABLE workflow_condition
(
    id                           integer not null primary key autoincrement,
    workspace_id                 integer not null,
    -- Set means the row is that workflow's own: not in the workspace's
    -- list, pointed at by nothing else, and gone when the workflow is.
    workflow_id                  integer,
    name                         varchar(120) not null,
    type                         varchar(16) not null,
    property                     varchar(32),
    check_by                     varchar(16),
    negate                       boolean not null default false,
    function_id                  integer,
    icon                         varchar(40),
    constraint ck_workflow_condition_shape CHECK ((workflow_id IS NOT NULL) OR (((((type) IN ('ANY_OF', 'ALL_OF')) AND (property IS NULL) AND (check_by IS NULL)) OR (((type) = 'FUNCTION') AND (function_id IS NOT NULL) AND (property IS NULL) AND (check_by IS NULL)) OR (((type) IN ('SLACK', 'JIRA', 'TIME')) AND (property IS NOT NULL) AND (check_by IS NOT NULL)) OR (((type) = 'VALUE') AND (property IS NULL) AND (check_by IS NOT NULL))))),
    constraint ck_workflow_condition_type CHECK (((type) IN ('SLACK', 'JIRA', 'TIME', 'FUNCTION', 'ANY_OF', 'ALL_OF', 'VALUE'))),
    constraint workflow_condition_function_id_fkey FOREIGN KEY (function_id) REFERENCES workflow_function(id),
    constraint workflow_condition_team_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE
);

CREATE TABLE workflow_condition_argument
(
    condition_id integer not null,
    position     integer not null,
    name         varchar(64) not null,
    expression   text not null,
    mode         varchar(16) not null,
    primary key (condition_id, position),
    constraint workflow_condition_argument_condition_id_fkey FOREIGN KEY (condition_id) REFERENCES workflow_condition(id) ON DELETE CASCADE
);

CREATE TABLE workflow_condition_member
(
    condition_id                 integer not null,
    position                     integer not null,
    member_id                    integer not null,
    primary key (condition_id, position),
    constraint workflow_condition_member_condition_id_fkey FOREIGN KEY (condition_id) REFERENCES workflow_condition(id) ON DELETE CASCADE,
    constraint workflow_condition_member_member_id_fkey FOREIGN KEY (member_id) REFERENCES workflow_condition(id) ON DELETE CASCADE
);

CREATE TABLE workflow_condition_value
(
    condition_id                 integer not null,
    position                     integer not null,
    value                        varchar(500) not null,
    primary key (condition_id, position),
    constraint workflow_condition_value_condition_id_fkey FOREIGN KEY (condition_id) REFERENCES workflow_condition(id) ON DELETE CASCADE
);

CREATE TABLE workflow_edge
(
    id                           integer not null primary key autoincrement,
    workflow_id                  integer not null,
    source_key                   varchar(64) not null,
    target_key                   varchar(64) not null,
    branch                       varchar(8),
    constraint uk_workflow_edge UNIQUE (workflow_id, source_key, target_key),
    constraint workflow_edge_workflow_id_fkey FOREIGN KEY (workflow_id) REFERENCES workflow(id) ON DELETE CASCADE
);

CREATE TABLE workflow_execution
(
    id                           integer not null primary key autoincrement,
    workspace_id                 integer not null,
    workflow_id                  integer not null,
    workflow_name                varchar(255) not null,
    status                       varchar(16) not null,
    trigger_type                 varchar(16) not null,
    started_at                   timestamp not null,
    finished_at                  timestamp,
    input                        text,
    error                        varchar(1000),
    stopped_at_node_key          varchar(64),
    stopped_reason               varchar(500),
    carried                      text,
    started_from                 integer,
    fired_trigger_id             integer,
    stop_requested               boolean not null default false,
    constraint ck_execution_status CHECK (((status) IN ('RUNNING', 'COMPLETED', 'FAILED', 'STOPPED'))),
    constraint ck_execution_trigger CHECK (((trigger_type) IN ('WEBHOOK', 'MANUAL', 'SCHEDULE', 'API'))),
    constraint fk_workflow_execution_started_from FOREIGN KEY (started_from) REFERENCES workflow_execution(id) ON DELETE SET NULL
);

CREATE TABLE workflow_function
(
    id                           integer not null primary key autoincrement,
    workspace_id                 integer,
    name                         varchar(120) not null,
    description                  varchar(4000),
    timeout_seconds              integer,
    edited_at                    timestamp,
    edited_by                    varchar(120),
    source                       text not null,
    return_type                  varchar(16) not null,
    last_modified_at             timestamp not null,
    last_modified_by             varchar(120) not null,
    scope                        varchar(16) not null default 'WORKSPACE',
    plugin_id                    integer,
    typescript                   text,
    return_object_id             integer,
    constraint uk_workflow_function_name UNIQUE (workspace_id, name),
    constraint ck_workflow_function_owner CHECK (((((scope) = 'WORKSPACE') AND (workspace_id IS NOT NULL) AND (plugin_id IS NULL)) OR (((scope) = 'PLUGIN') AND (workspace_id IS NULL) AND (plugin_id IS NOT NULL)))),
    constraint ck_workflow_function_return CHECK (((return_type) IN ('STRING', 'NUMBER', 'BOOLEAN', 'OBJECT', 'MAP', 'ARRAY', 'NONE'))),
    constraint ck_workflow_function_return_object CHECK (((((return_type) = 'OBJECT') AND (return_object_id IS NOT NULL)) OR (((return_type) != 'OBJECT') AND (return_object_id IS NULL)))),
    constraint ck_workflow_function_scope CHECK (((scope) IN ('WORKSPACE', 'PLUGIN'))),
    constraint workflow_function_plugin_id_fkey FOREIGN KEY (plugin_id) REFERENCES plugin(id) ON DELETE CASCADE,
    constraint workflow_function_team_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE
);

CREATE TABLE workflow_function_external
(
    function_id                  integer not null,
    variable_id                  integer not null,
    position                     integer not null,
    primary key (function_id, position),
    constraint workflow_function_external_function_id_fkey FOREIGN KEY (function_id) REFERENCES workflow_function(id) ON DELETE CASCADE,
    constraint workflow_function_external_variable_id_fkey FOREIGN KEY (variable_id) REFERENCES workspace_variable(id) ON DELETE RESTRICT
);

CREATE TABLE workflow_function_import
(
    function_id                  integer not null,
    position                     integer not null,
    imported_id                  integer not null,
    import_name                  varchar(64) not null,
    primary key (function_id, position),
    constraint uq_workflow_function_import_name UNIQUE (function_id, import_name),
    constraint workflow_function_import_function_id_fkey FOREIGN KEY (function_id) REFERENCES workflow_function(id) ON DELETE CASCADE
);

CREATE TABLE workflow_function_library
(
    function_id                  integer not null,
    position                     integer not null,
    imported_id                  integer not null,
    import_name                  varchar(64) not null,
    primary key (function_id, position),
    constraint uq_workflow_function_library_name UNIQUE (function_id, import_name),
    constraint workflow_function_library_function_id_fkey FOREIGN KEY (function_id) REFERENCES workflow_function(id) ON DELETE CASCADE
);

CREATE TABLE workflow_function_param
(
    function_id                  integer not null,
    position                     integer not null,
    name                         varchar(64) not null,
    type                         varchar(16) not null,
    object_id                    integer,
    -- Whether a caller has to supply it, and what arrives when it does not.
    -- True for everything written before this existed, which is what every
    -- caller already assumed: a declared parameter was one you passed.
    required                     boolean not null default true,
    default_json                 text,
    primary key (function_id, position),
    constraint ck_workflow_function_param_object CHECK (((((type) = 'OBJECT') AND (object_id IS NOT NULL)) OR (((type) != 'OBJECT') AND (object_id IS NULL)))),
    constraint ck_workflow_function_param_type CHECK (((type) IN ('STRING', 'NUMBER', 'BOOLEAN', 'OBJECT', 'MAP', 'ARRAY', 'CONNECTION'))),
    constraint workflow_function_param_function_id_fkey FOREIGN KEY (function_id) REFERENCES workflow_function(id) ON DELETE CASCADE
);

CREATE TABLE workflow_node
(
    id                           integer not null primary key autoincrement,
    workflow_id                  integer not null,
    node_key                     varchar(64) not null,
    kind                         varchar(16) not null,
    name                         varchar(255) not null,
    description                  varchar(500),
    position_x                   float not null,
    position_y                   float not null,
    trigger_id                   integer,
    action_id                    integer,
    condition_id                 integer,
    agent_id                     integer,
    output_name                  varchar(60),
    icon                         varchar(40),
    object_id                    integer,
    image_model_id               integer,
    image_size                   varchar(16),
    image_quality                varchar(16),
    image_style                  varchar(16),
    output_object_id             integer,
    output_node_key              varchar(64),
    yes_label                    varchar(40),
    no_label                     varchar(40),
    orientation                  varchar(16),
    fallback_enabled             boolean not null default false,
    retry_attempts               integer,
    retry_backoff_seconds        integer,
    retry_multiplier             float,
    retry_max_wait_seconds       integer,
    retry_jitter                 float,
    retry_budget_seconds         integer,
    enabled                      boolean not null default true,
    constraint uk_workflow_node UNIQUE (workflow_id, node_key),
    constraint ck_workflow_node_retry_multiplier CHECK (((retry_multiplier IS NULL) OR ((retry_multiplier >= 1) AND (retry_multiplier <= 10)))),
    constraint ck_workflow_node_retry_max_wait CHECK (((retry_max_wait_seconds IS NULL) OR ((retry_max_wait_seconds >= 1) AND (retry_max_wait_seconds <= 3600)))),
    constraint ck_workflow_node_retry_jitter CHECK (((retry_jitter IS NULL) OR ((retry_jitter >= 0) AND (retry_jitter <= 1)))),
    constraint ck_workflow_node_retry_budget CHECK (((retry_budget_seconds IS NULL) OR ((retry_budget_seconds >= 1) AND (retry_budget_seconds <= 86400)))),
    constraint ck_workflow_node_kind CHECK (((kind) IN ('TRIGGER', 'AGENT', 'ACTION', 'CONDITION', 'OBJECT', 'SESSION', 'IMAGE'))),
    constraint workflow_node_action_id_fkey FOREIGN KEY (action_id) REFERENCES workflow_action(id) ON DELETE SET NULL,
    constraint workflow_node_agent_id_fkey FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE SET NULL,
    constraint workflow_node_condition_id_fkey FOREIGN KEY (condition_id) REFERENCES workflow_condition(id) ON DELETE SET NULL,
    constraint workflow_node_object_id_fkey FOREIGN KEY (object_id) REFERENCES workflow_object(id) ON DELETE SET NULL,
    constraint workflow_node_trigger_id_fkey FOREIGN KEY (trigger_id) REFERENCES workflow_trigger(id) ON DELETE SET NULL,
    constraint workflow_node_workflow_id_fkey FOREIGN KEY (workflow_id) REFERENCES workflow(id) ON DELETE CASCADE
);

CREATE TABLE workflow_node_mapping
(
    workflow_node_id             integer not null,
    position                     integer not null,
    name                         varchar(64) not null,
    expression                   text not null,
    mode                         varchar(16) not null default 'VALUE',
    source_node_key              varchar(64),
    -- What a field a node writes holds, where the node names its own fields.
    -- The three a saved object's property has, asked here for the same reason.
    -- Null is untyped, which is every mapping typed by the definition it
    -- belongs to. See `V272__node_field_type.sql` on Postgres.
    field_kind                   varchar(16),
    field_element_kind           varchar(16),
    field_ref_object_id          integer,
    primary key (workflow_node_id, position),
    constraint workflow_node_mapping_workflow_node_id_fkey FOREIGN KEY (workflow_node_id) REFERENCES workflow_node(id) ON DELETE CASCADE
);

CREATE TABLE workflow_object
(
    id                           integer not null primary key autoincrement,
    -- Exactly one owner: the workspace that drew the shape, or the plugin
    -- that exports it. A plugin's row is replaced wholesale on every load and
    -- goes when the plugin is unloaded.
    workspace_id                 integer,
    plugin_id                    integer,
    name                         varchar(64) not null,
    description                  text,
    created_at                   timestamp not null,
    created_by                   varchar(255) not null,
    last_modified_at             timestamp not null,
    last_modified_by             varchar(255) not null,
    constraint uk_workflow_object_name UNIQUE (workspace_id, name),
    constraint uk_workflow_object_plugin_name UNIQUE (plugin_id, name),
    constraint workflow_object_has_one_owner CHECK ((workspace_id IS NULL) <> (plugin_id IS NULL)),
    constraint workflow_object_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE,
    constraint workflow_object_plugin_id_fkey FOREIGN KEY (plugin_id) REFERENCES plugin(id) ON DELETE CASCADE
);

CREATE INDEX idx_workflow_object_plugin ON workflow_object (plugin_id);

CREATE TABLE workflow_publication
(
    id                           integer not null primary key autoincrement,
    workflow_id                  integer not null,
    published_at                 timestamp not null default CURRENT_TIMESTAMP,
    published_by                 varchar(120) not null default 'system',
    graph                        jsonb not null,
    restored_from                integer,
    constraint workflow_publication_workflow_id_fkey FOREIGN KEY (workflow_id) REFERENCES workflow(id) ON DELETE CASCADE
);

CREATE TABLE workspace_artifact
(
    id                           integer not null primary key autoincrement,
    workspace_id                 integer not null,
    name                         varchar(255) not null,
    description                  text not null,
    content_type                 varchar(120) not null,
    size_bytes                   integer not null,
    location                     varchar(1000) not null,
    saved_by                     varchar(255) not null,
    saved_at                     timestamp not null default current_timestamp,
    constraint workspace_artifact_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE
);

CREATE INDEX idx_workspace_artifact_workspace ON workspace_artifact (workspace_id, saved_at DESC);

CREATE TABLE workflow_trigger
(
    id                           integer not null primary key autoincrement,
    workspace_id                 integer not null,
    name                         varchar(255) not null,
    type                         varchar(24) not null,
    connection_id                integer,
    action                       varchar(32),
    cron                         varchar(120),
    timezone                     varchar(64),
    enabled                      boolean not null default true,
    last_fired_at                timestamp,
    payload                      text,
    condition_id                 integer,
    icon                         varchar(40),
    webhook_path                 varchar(120),
    object_id                    integer,
    auth_type                    varchar(16) not null default 'NONE',
    auth_function_id             integer,
    workflow_id                  integer,
    constraint ck_workflow_trigger_action CHECK (((action IS NULL) OR ((action) IN ('MENTION', 'REPLY', 'MESSAGE', 'ISSUE_CREATED', 'ISSUE_UPDATED')))),
    constraint ck_workflow_trigger_auth CHECK ((((auth_type) IN ('NONE', 'FUNCTION')) AND (((auth_type) != 'FUNCTION') OR (auth_function_id IS NOT NULL)))),
    constraint ck_workflow_trigger_shape CHECK ((workflow_id IS NOT NULL) OR (((((type) = 'INCOMING_CONNECTION') AND (connection_id IS NOT NULL) AND (action IS NOT NULL)) OR (((type) = 'SCHEDULED') AND (cron IS NOT NULL)) OR (((type) = 'WEBHOOK') AND (webhook_path IS NOT NULL) AND (object_id IS NOT NULL))))),
    constraint ck_workflow_trigger_type CHECK (((type) IN ('INCOMING_CONNECTION', 'SCHEDULED', 'WEBHOOK'))),
    constraint workflow_trigger_auth_function_id_fkey FOREIGN KEY (auth_function_id) REFERENCES workflow_function(id),
    constraint workflow_trigger_condition_id_fkey FOREIGN KEY (condition_id) REFERENCES workflow_condition(id),
    constraint workflow_trigger_team_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE,
    constraint workflow_trigger_workflow_id_fkey FOREIGN KEY (workflow_id) REFERENCES workflow(id) ON DELETE CASCADE
);

CREATE TABLE workflow_trigger_watch
(
    trigger_id                   integer not null,
    connection_id                integer not null,
    primary key (trigger_id, connection_id),
    constraint workflow_trigger_watch_trigger_id_fkey FOREIGN KEY (trigger_id) REFERENCES workflow_trigger(id) ON DELETE CASCADE
);

CREATE TABLE workspace
(
    id                           integer not null primary key autoincrement,
    name                         varchar(255) not null,
    description                  varchar(500),
    ldap_group                   varchar(255),
    companion_model_id           integer,
    transcription_model_id       integer,
    speech_model_id              integer,
    compact_after_tokens         integer,
    compaction_summary_tokens    integer,
    compaction_model_id          integer,
    image_model_id               integer,
    quick_chat_model_id          integer,
    quick_chat_may_write         boolean not null default false,
    chat_show_timestamps         boolean not null default false,
    default_memory_share         integer,
    voice_pause_ends_turn_ms     integer,
    voice_speech_over_room_percent integer,
    voice_barge_in_ms            integer,
    voice_unattended_microphone_ms integer,
    voice_speech_chunking        varchar(16) not null default 'SENTENCE',
    task_max_turns               integer,
    agent_max_subagents          integer,
    command_marker               varchar(3),
    script_timeout_seconds       integer,
    tool_timeout_seconds         integer,
    constraint uk_workspace_name UNIQUE (name),
    constraint ck_workspace_default_memory_share CHECK (default_memory_share IS NULL OR (default_memory_share >= 1 AND default_memory_share <= 50)),
    constraint ck_workspace_voice_pause_ends_turn CHECK (voice_pause_ends_turn_ms IS NULL OR (voice_pause_ends_turn_ms BETWEEN 1500 AND 10000)),
    constraint ck_workspace_voice_speech_over_room CHECK (voice_speech_over_room_percent IS NULL OR (voice_speech_over_room_percent BETWEEN 120 AND 600)),
    constraint ck_workspace_voice_unattended_microphone CHECK (voice_unattended_microphone_ms IS NULL OR (voice_unattended_microphone_ms BETWEEN 300000 AND 3600000)),
    constraint ck_workspace_voice_speech_chunking CHECK ((voice_speech_chunking) IN ('NONE', 'SENTENCE', 'PARAGRAPH')),
    constraint ck_workspace_task_max_turns CHECK (task_max_turns IS NULL OR (task_max_turns BETWEEN 1 AND 200)),
    constraint ck_workspace_agent_max_subagents CHECK (agent_max_subagents IS NULL OR (agent_max_subagents BETWEEN 0 AND 100)),
    constraint ck_workspace_script_timeout CHECK (script_timeout_seconds IS NULL OR (script_timeout_seconds BETWEEN 1 AND 600)),
    constraint ck_workspace_tool_timeout CHECK (tool_timeout_seconds IS NULL OR (tool_timeout_seconds BETWEEN 1 AND 600)),
    constraint workspace_quick_chat_model_id_fkey FOREIGN KEY (quick_chat_model_id) REFERENCES llm_model(id) ON DELETE SET NULL,
    constraint workspace_image_model_id_fkey FOREIGN KEY (image_model_id) REFERENCES llm_model(id) ON DELETE SET NULL,
    constraint workspace_speech_model_id_fkey FOREIGN KEY (speech_model_id) REFERENCES llm_model(id) ON DELETE SET NULL,
    constraint workspace_transcription_model_id_fkey FOREIGN KEY (transcription_model_id) REFERENCES llm_model(id) ON DELETE SET NULL
);

CREATE TABLE workspace_audit
(
    id                           integer not null primary key autoincrement,
    workspace_id                 integer,
    old_workspace_name           varchar(255),
    new_workspace_name           varchar(255),
    operation_type               varchar(16),
    date                         timestamp not null,
    user_id                      varchar(255) not null,
    category                     varchar(16) not null,
    message                      varchar(500) not null,
    constraint ck_workspace_audit_category CHECK (((category) IN ('WORKSPACE', 'WORKFLOW', 'AGENT', 'INTEGRATION', 'MODEL', 'MEMORY', 'OBJECT', 'CHAT', 'SHELL', 'TASK'))),
    constraint ck_workspace_audit_operation_type CHECK (((operation_type) IN ('ADD', 'REMOVE', 'RENAME')))
);

CREATE TABLE workspace_connection
(
    id                           integer not null primary key autoincrement,
    workspace_id                 integer not null,
    connection_id                integer,
    name                         varchar(255) not null,
    type                         varchar(24) not null,
    url                          varchar(1000) not null,
    url_override                 varchar(1000),
    -- V294: which plugin-declared kind of host this is (key/name); HTTP only. Issue #363.
    plugin_type                  varchar(120),
    auth_type                    varchar(16) not null default 'NONE',
    secret                       varchar(4000),
    secret_variable_id           integer,
    last_check_status            varchar(16),
    last_check_message           varchar(500),
    last_checked_at              timestamp,
    app_token                    varchar(4000),
    app_token_variable_id        integer,
    user_token                   varchar(4000),
    user_token_variable_id       integer,
    smtp_port                    integer,
    smtp_username                varchar(320),
    smtp_from                    varchar(320),
    smtp_security                varchar(16) not null default 'STARTTLS',
    constraint uk_workspace_connection_name UNIQUE (workspace_id, name),
    constraint ck_workspace_connection_app_token CHECK (app_token_variable_id IS NULL OR app_token IS NULL),
    constraint ck_workspace_connection_user_token CHECK (user_token_variable_id IS NULL OR user_token IS NULL),
    constraint ck_workspace_connection_auth CHECK (((auth_type) IN ('NONE', 'API_KEY', 'BEARER_TOKEN', 'BASIC'))),
    constraint ck_workspace_connection_secret CHECK (secret_variable_id IS NULL OR secret IS NULL),
    constraint ck_workspace_connection_check CHECK (((last_check_status IS NULL) OR ((last_check_status) IN ('CONNECTED', 'FAILED')))),
    constraint ck_workspace_connection_smtp_security CHECK (((smtp_security) IN ('NONE', 'STARTTLS', 'TLS'))),
    constraint ck_workspace_connection_type CHECK (((type) IN ('SLACK', 'SMTP', 'HTTP'))),
    constraint team_connection_connection_id_fkey FOREIGN KEY (connection_id) REFERENCES connection(id) ON DELETE SET NULL
);

CREATE TABLE workspace_connection_header
(
    workspace_connection_id      integer not null,
    position                     integer not null,
    name                         varchar(255) not null,
    value                        varchar(1000) not null,
    primary key (workspace_connection_id, position),
    constraint team_connection_header_team_connection_id_fkey FOREIGN KEY (workspace_connection_id) REFERENCES workspace_connection(id) ON DELETE CASCADE
);

CREATE TABLE workspace_issue
(
    id                           integer not null primary key autoincrement,
    workspace_id                 integer not null,
    number                       integer not null,
    title                        varchar(200) not null,
    description                  text,
    -- The key of one of the workspace's own statuses (workspace_issue_status
    -- below); the four every workspace begins with are seeded in code when the
    -- workspace is made. See V296 in the Postgres history. Issue #428.
    status                       varchar(32) not null default 'OPEN',
    reporter                     varchar(120) not null,
    assignee_kind                varchar(16),
    assignee_id                  varchar(120),
    created_at                   timestamp not null default CURRENT_TIMESTAMP,
    last_modified_at             timestamp not null default CURRENT_TIMESTAMP,
    last_modified_by             varchar(120) not null default 'system',
    last_comment_at              timestamp,

    -- What the issue is, or null for untyped - which is a real state and what
    -- every issue filed before types existed still says. See V197 in the
    -- Postgres history for why this is a row rather than a reserved label, and
    -- why the clause below is SET NULL rather than the RESTRICT the product
    -- behaves like: deleting a type that issues carry is refused in words, and
    -- the one deletion left for the database is the whole workspace going, on
    -- which SQLite would otherwise refuse depending on which cascade ran first.
    type_id                      integer,
    constraint workspace_issue_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE,
    constraint workspace_issue_type_id_fkey FOREIGN KEY (type_id) REFERENCES workspace_issue_type(id) ON DELETE SET NULL
);

CREATE TABLE workspace_issue_attachment
(
    id                           integer not null primary key autoincrement,
    workspace_id                 integer not null,
    issue_id                     integer,
    comment_id                   integer,
    filename                     varchar(255) not null,
    content_type                 varchar(120) not null,
    size_bytes                   integer not null,
    location                     varchar(1000) not null,
    uploaded_at                  timestamp not null default CURRENT_TIMESTAMP,
    uploaded_by                  varchar(120) not null default '',
    constraint workspace_issue_attachment_comment_id_fkey FOREIGN KEY (comment_id) REFERENCES workspace_issue_comment(id) ON DELETE CASCADE,
    constraint workspace_issue_attachment_issue_id_fkey FOREIGN KEY (issue_id) REFERENCES workspace_issue(id) ON DELETE CASCADE,
    constraint workspace_issue_attachment_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE
);

CREATE TABLE workspace_issue_comment
(
    id                           integer not null primary key autoincrement,
    issue_id                     integer not null,
    author                       varchar(120) not null,
    content                      text not null,
    created_at                   timestamp not null default CURRENT_TIMESTAMP,
    edited_at                    timestamp,
    constraint workspace_issue_comment_issue_id_fkey FOREIGN KEY (issue_id) REFERENCES workspace_issue(id) ON DELETE CASCADE
);

CREATE TABLE workspace_issue_event
(
    id                           integer not null primary key autoincrement,
    issue_id                     integer not null,
    kind                         varchar(16) not null,
    actor                        varchar(120) not null,
    was                          text,
    became                       text,
    at                           timestamp not null default CURRENT_TIMESTAMP,
    constraint workspace_issue_event_issue_id_fkey FOREIGN KEY (issue_id) REFERENCES workspace_issue(id) ON DELETE CASCADE
);

CREATE TABLE workspace_issue_label
(
    issue_id                     integer not null,
    label                        varchar(60) not null,
    primary key (issue_id, label),
    constraint workspace_issue_label_issue_id_fkey FOREIGN KEY (issue_id) REFERENCES workspace_issue(id) ON DELETE CASCADE
);

CREATE TABLE workspace_issue_link
(
    id                           integer not null primary key autoincrement,
    issue_id                     integer not null,
    url                          varchar(2000) not null,
    title                        varchar(200),
    added_at                     timestamp not null default CURRENT_TIMESTAMP,
    added_by                     varchar(120) not null default '',
    constraint workspace_issue_link_issue_id_fkey FOREIGN KEY (issue_id) REFERENCES workspace_issue(id) ON DELETE CASCADE
);

CREATE TABLE workspace_issue_observer
(
    id                           integer not null primary key autoincrement,
    issue_id                     integer not null,
    observer_kind                varchar(16) not null,
    observer_id                  varchar(120) not null,
    added_at                     timestamp not null default CURRENT_TIMESTAMP,
    added_by                     varchar(120) not null default '',
    constraint workspace_issue_observer_issue_id_fkey FOREIGN KEY (issue_id) REFERENCES workspace_issue(id) ON DELETE CASCADE
);

CREATE TABLE workspace_issue_relation
(
    id                           integer not null primary key autoincrement,
    issue_id                     integer not null,
    other_issue_id               integer not null,
    kind                         varchar(16) not null,
    linked_at                    timestamp not null default CURRENT_TIMESTAMP,
    linked_by                    varchar(120) not null default '',
    constraint workspace_issue_relation_issue_id_fkey FOREIGN KEY (issue_id) REFERENCES workspace_issue(id) ON DELETE CASCADE,
    constraint workspace_issue_relation_other_issue_id_fkey FOREIGN KEY (other_issue_id) REFERENCES workspace_issue(id) ON DELETE CASCADE
);

-- The kinds of thing a workspace files: bug, feature, and whatever else it
-- decides. A catalogue rather than a reserved label prefix, for the three
-- reasons written out in the Postgres V197 - one type per issue, a type that
-- exists while nothing carries it, and a name that can be changed in one place.
CREATE TABLE workspace_issue_type
(
    id                           integer not null primary key autoincrement,
    workspace_id                 integer not null,
    name                         varchar(60) not null,
    constraint workspace_issue_type_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE
);

-- The statuses a workspace's issues move through, decided by the workspace:
-- a key an issue stores, a label people read, a colour, an order, and two
-- flags - where a new issue lands (exactly one) and what counts as done (any
-- number). Seeded in code when a workspace is made. See the Postgres V296.
CREATE TABLE workspace_issue_status
(
    id                           integer not null primary key autoincrement,
    workspace_id                 integer not null,
    key                          varchar(32) not null,
    label                        varchar(60) not null,
    color                        varchar(32),
    position                     integer not null default 0,
    initial                      boolean not null default 0,
    closed                       boolean not null default 0,
    created_at                   timestamp not null default CURRENT_TIMESTAMP,
    constraint workspace_issue_status_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE
);

-- The size presets a workspace's image nodes pick from: a name, a width and a
-- height in pixels, and a menu order. Seeded in code when a workspace is made.
-- See the Postgres V297. Issue #431.
CREATE TABLE workspace_image_size_preset
(
    id                           integer not null primary key autoincrement,
    workspace_id                 integer not null,
    name                         varchar(60) not null,
    width                        integer not null,
    height                       integer not null,
    position                     integer not null default 0,
    created_at                   timestamp not null default CURRENT_TIMESTAMP,
    constraint workspace_image_size_preset_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE
);

CREATE TABLE workspace_role
(
    workspace_id                 integer not null,
    role_id                      integer not null,
    primary key (workspace_id, role_id),
    constraint workspace_role_role_id_fkey FOREIGN KEY (role_id) REFERENCES security_role(id) ON DELETE CASCADE,
    constraint workspace_role_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE
);

-- The roles that administer a workspace, as against the ones above that only
-- open it. Meant to be a subset of workspace_role; the API enforces that on
-- save, since a role administering a workspace it cannot see is nothing.
CREATE TABLE workspace_admin_role
(
    workspace_id                 integer not null,
    role_id                      integer not null,
    primary key (workspace_id, role_id),
    constraint workspace_admin_role_role_id_fkey FOREIGN KEY (role_id) REFERENCES security_role(id) ON DELETE CASCADE,
    constraint workspace_admin_role_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE
);

CREATE TABLE workspace_variable
(
    id                           integer not null primary key autoincrement,
    workspace_id                 integer not null,
    catalog_id                   integer not null,
    name                         varchar(64) not null,
    type                         varchar(16) not null,
    value                        varchar(4096),
    created_at                   timestamp not null default CURRENT_TIMESTAMP,
    last_modified_at             timestamp not null default CURRENT_TIMESTAMP,
    last_modified_by             varchar(120) not null default '',
    kind                         varchar(16) not null default 'SECRET',
    description                  varchar(500),
    created_by                   varchar(120) not null default '',
    element_type                 varchar(16),
    custom_type                  varchar(120),
    type_arguments               text,
    constraint uk_workspace_variable_name UNIQUE (catalog_id, name),
    constraint ck_workspace_variable_kind CHECK (((kind) IN ('VALUE', 'SECRET'))),
    constraint ck_workspace_variable_type CHECK (((type) IN ('STRING', 'NUMBER', 'BOOLEAN', 'LIST'))),
    constraint ck_workspace_variable_element_type CHECK (element_type IS NULL OR element_type IN ('STRING', 'NUMBER', 'BOOLEAN')),
    constraint workspace_variable_vault_id_fkey FOREIGN KEY (catalog_id) REFERENCES variable_catalog(id) ON DELETE RESTRICT,
    constraint workspace_variable_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE
);

CREATE TABLE workspace_workflow
(
    id                           integer not null primary key autoincrement,
    workspace_id                 integer not null,
    workflow_id                  integer not null,
    enabled                      boolean not null default true,
    constraint uk_workspace_workflow UNIQUE (workspace_id, workflow_id),
    constraint team_workflow_team_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE,
    constraint team_workflow_workflow_id_fkey FOREIGN KEY (workflow_id) REFERENCES workflow(id) ON DELETE CASCADE
);


CREATE INDEX idx_agent_workspace_id ON agent (workspace_id);
CREATE INDEX idx_agent_skill_workspace ON agent_skill (workspace_id);
CREATE INDEX idx_skill_catalog ON agent_skill (catalog_id);
CREATE INDEX idx_agent_tool_workspace ON agent_tool (workspace_id);
CREATE UNIQUE INDEX app_user_username_key ON app_user (lower((username)));
CREATE UNIQUE INDEX app_user_token_hash_key ON app_user_token (token_hash);
CREATE INDEX app_user_token_user_idx ON app_user_token (user_id);
CREATE INDEX chat_answer_take_session_idx ON chat_answer_take (chat_session_id, message_index, id);
CREATE INDEX idx_chat_attachment_session ON chat_attachment (chat_session_id);
CREATE INDEX idx_chat_attachment_workspace ON chat_attachment (workspace_id);
CREATE INDEX idx_chat_session_owner ON chat_session (workspace_id, user_id, last_message_at DESC);
CREATE INDEX component_revision_component_idx ON component_revision (kind, component_id, recorded_at DESC, id DESC);
CREATE INDEX component_revision_recorded_idx ON component_revision (recorded_at);
CREATE UNIQUE INDEX component_template_name_key ON component_template (name);
CREATE INDEX idx_execution_log_execution ON execution_log (execution_id, sequence_no);
CREATE INDEX idx_execution_step_execution ON execution_step (execution_id, step_order);
CREATE INDEX issue_news_audience_idx ON issue_news (workspace_id, audience_kind, audience_name, id);
CREATE INDEX idx_llm_model_provider ON llm_model (provider_id);
CREATE UNIQUE INDEX llm_session_key_key ON llm_session (workspace_id, session_key);
CREATE INDEX llm_session_recent_idx ON llm_session (workspace_id, last_event_at DESC);
CREATE INDEX llm_session_event_session_idx ON llm_session_event (session_id, at, id);
CREATE INDEX llm_session_event_result_idx ON llm_session_event (session_id, at DESC, id DESC) WHERE result IS NOT NULL;
-- The tail a live reader follows, by the order lines were written rather than by
-- when they were said. See V205 for why the (session_id, at, id) index above is
-- not the one that serves it.
CREATE INDEX llm_session_event_tail_idx ON llm_session_event (session_id, id);
CREATE INDEX idx_mcp_server_workspace_id ON mcp_server (workspace_id);
CREATE INDEX idx_memory_catalog ON memory (catalog_id);
CREATE INDEX idx_memory_modified ON memory (catalog_id, last_modified_at DESC);
CREATE INDEX idx_memory_catalog_workspace ON memory_catalog (workspace_id);
CREATE INDEX idx_model_provider_workspace ON model_provider (workspace_id);
CREATE INDEX idx_model_usage_day_model ON model_usage_day (model_id, day);
CREATE INDEX idx_object_property_ref ON object_property (ref_object_id);
CREATE UNIQUE INDEX password_reset_hash_key ON password_reset (token_hash);
CREATE INDEX password_reset_user_idx ON password_reset (user_id);
CREATE UNIQUE INDEX uk_plugin_key ON plugin (plugin_key);
CREATE UNIQUE INDEX plugin_parameter_unique_idx ON plugin_parameter (plugin_id, workspace_id, name);
CREATE INDEX plugin_parameter_workspace_idx ON plugin_parameter (workspace_id, plugin_id);
CREATE UNIQUE INDEX proxy_rule_name_key ON proxy_rule (name);
CREATE INDEX proxy_rule_position_idx ON proxy_rule ("position", id);
CREATE INDEX execution_time_idx ON scheduled_tasks (execution_time);
CREATE INDEX last_heartbeat_idx ON scheduled_tasks (last_heartbeat);
CREATE INDEX priority_execution_time_idx ON scheduled_tasks (priority DESC, execution_time);
CREATE UNIQUE INDEX ux_security_role_name ON security_role (lower((name)));
CREATE INDEX shell_choice_idx ON shell (enabled, status, name);
CREATE UNIQUE INDEX shell_name_key ON shell (name);
CREATE INDEX shell_session_agent_idx ON shell_session (agent_id, state);
CREATE INDEX shell_session_open_idx ON shell_session (state, last_used_at);
CREATE INDEX spring_ai_chat_memory_conversation_id_sequence_id_idx ON spring_ai_chat_memory (conversation_id, sequence_id);
CREATE INDEX spring_ai_chat_memory_conversation_id_timestamp_idx ON spring_ai_chat_memory (conversation_id, "timestamp");
CREATE UNIQUE INDEX spring_session_ix1 ON spring_session (session_id);
CREATE INDEX spring_session_ix2 ON spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON spring_session (principal_name);
CREATE INDEX task_queued_idx ON task (status, created_at);
CREATE INDEX task_message_task_idx ON task_message (task_id, sent_at, id);
CREATE INDEX task_picture_task_idx ON task_picture (task_id, drawn_at, id);
CREATE INDEX idx_trigger_firing_trigger ON trigger_firing (trigger_id, at DESC);
CREATE INDEX idx_variable_catalog_workspace ON variable_catalog (workspace_id);
CREATE INDEX idx_workflow_action_workspace ON workflow_action (workspace_id);
CREATE INDEX idx_workflow_condition_workspace ON workflow_condition (workspace_id);
CREATE INDEX idx_workflow_edge_workflow ON workflow_edge (workflow_id);
CREATE INDEX idx_workflow_execution_workflow ON workflow_execution (workflow_id, started_at DESC);
CREATE INDEX idx_workflow_execution_workspace ON workflow_execution (workspace_id, started_at DESC);
CREATE INDEX idx_workflow_function_plugin ON workflow_function (plugin_id);
CREATE INDEX idx_workflow_function_workspace ON workflow_function (workspace_id);
CREATE UNIQUE INDEX uk_workflow_function_plugin_name ON workflow_function (name) WHERE ((scope) = 'PLUGIN');
CREATE INDEX idx_workflow_function_external_variable ON workflow_function_external (variable_id);
CREATE INDEX idx_agent_tool_external_variable ON agent_tool_external (variable_id);
CREATE INDEX idx_workflow_node_action ON workflow_node (action_id) WHERE (action_id IS NOT NULL);
CREATE INDEX idx_workflow_node_agent ON workflow_node (agent_id);
CREATE INDEX idx_workflow_node_condition ON workflow_node (condition_id) WHERE (condition_id IS NOT NULL);
CREATE INDEX idx_workflow_node_trigger ON workflow_node (trigger_id) WHERE (trigger_id IS NOT NULL);
CREATE INDEX idx_workflow_node_workflow ON workflow_node (workflow_id);
CREATE INDEX workflow_publication_workflow_idx ON workflow_publication (workflow_id, id DESC);
CREATE INDEX idx_workflow_trigger_connection ON workflow_trigger (connection_id, action) WHERE enabled;
CREATE INDEX idx_workflow_trigger_workspace ON workflow_trigger (workspace_id);
CREATE UNIQUE INDEX uk_workflow_trigger_webhook_path ON workflow_trigger (webhook_path) WHERE (webhook_path IS NOT NULL);
CREATE INDEX idx_workspace_audit_category ON workspace_audit (category);
CREATE INDEX idx_workspace_audit_workspace_id ON workspace_audit (workspace_id);
CREATE INDEX idx_workspace_connection_workspace_id ON workspace_connection (workspace_id);
CREATE UNIQUE INDEX workspace_issue_number_key ON workspace_issue (workspace_id, number);
CREATE INDEX workspace_issue_status_idx ON workspace_issue (workspace_id, status);
CREATE INDEX workspace_issue_attachment_comment_idx ON workspace_issue_attachment (comment_id);
CREATE INDEX workspace_issue_attachment_issue_idx ON workspace_issue_attachment (issue_id, uploaded_at);
CREATE INDEX workspace_issue_attachment_workspace_idx ON workspace_issue_attachment (workspace_id);
CREATE INDEX workspace_issue_comment_issue_idx ON workspace_issue_comment (issue_id, created_at);
CREATE INDEX workspace_issue_event_issue_idx ON workspace_issue_event (issue_id, at, id);
CREATE INDEX workspace_issue_link_issue_idx ON workspace_issue_link (issue_id, added_at);
CREATE UNIQUE INDEX workspace_issue_observer_key ON workspace_issue_observer (issue_id, observer_kind, observer_id);
CREATE UNIQUE INDEX workspace_issue_relation_pair_key ON workspace_issue_relation (issue_id, other_issue_id);
CREATE INDEX workspace_issue_relation_other_idx ON workspace_issue_relation (other_issue_id, linked_at);
CREATE UNIQUE INDEX workspace_issue_type_name_key ON workspace_issue_type (workspace_id, lower(name));
CREATE INDEX workspace_issue_type_idx ON workspace_issue (workspace_id, type_id);
CREATE UNIQUE INDEX workspace_issue_status_key_key ON workspace_issue_status (workspace_id, upper(key));
CREATE UNIQUE INDEX workspace_issue_status_initial_key ON workspace_issue_status (workspace_id) WHERE initial;
CREATE INDEX workspace_issue_status_workspace_idx ON workspace_issue_status (workspace_id, position);
CREATE INDEX workspace_image_size_preset_workspace_idx ON workspace_image_size_preset (workspace_id, position);
CREATE INDEX ix_agent_tool_import_imported ON agent_tool_import (imported_id);
CREATE INDEX ix_agent_tool_library_imported ON agent_tool_library (imported_id);
CREATE INDEX ix_workflow_function_library_imported ON workflow_function_library (imported_id);
CREATE INDEX ix_workflow_function_import_imported ON workflow_function_import (imported_id);
CREATE INDEX idx_workspace_variable_catalog ON workspace_variable (catalog_id);
CREATE INDEX idx_workspace_variable_workspace ON workspace_variable (workspace_id);
CREATE INDEX idx_workspace_workflow_workspace_id ON workspace_workflow (workspace_id);


-- The rows a migration put there rather than a table's shape, which is all of
-- one migration: V69 gives the installation its built-in role. Everything else
-- the ninety-eight insert is a backfill of data an older installation already
-- had, and a database created by this file has none of it to fill.
INSERT INTO security_role (name, description, builtin)
VALUES ('Administrators',
        'Sees the Admin section and every workspace, whatever else is assigned. Built in: this role cannot be edited or removed.',
        1);

INSERT INTO security_role_scope (role_id, scope)
SELECT id, 'ADMIN'
FROM security_role
WHERE builtin;

-- A workflow's own definition is unique within that workflow; a shared one is
-- unique in the workspace. See V264 on the Postgres side for why.
create unique index if not exists uk_workflow_action_name_shared
    on workflow_action (workspace_id, name) where workflow_id is null;
create unique index if not exists uk_workflow_condition_name_shared
    on workflow_condition (workspace_id, name) where workflow_id is null;
create unique index if not exists uk_workflow_trigger_name_shared
    on workflow_trigger (workspace_id, name) where workflow_id is null;
