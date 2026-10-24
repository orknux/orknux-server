-- Postgres V428: a model kind VIDEO, and design_video - the videos made on
-- Design's Videos page, one row from the request to the file kept. Issue #658.
--
-- SQLite cannot change a CHECK in place, so llm_model is built again with the
-- wider one, as V64 did: foreign keys off, the new table renamed into place,
-- outside a transaction (V106__video_generation.sql.conf). Every column it has
-- gained since V64 is carried: cached_input_cost_per_million (V77),
-- cache_write_cost_per_million (V82), answer_limit_minutes (V89), takes_video
-- (V96), takes_audio (V101) and negative_prompt (V104).

PRAGMA foreign_keys = OFF;

-- A savepoint rather than BEGIN, which Flyway's SQLite parser reads as the start of a trigger body.
SAVEPOINT video_generation;

CREATE TABLE llm_model_new
(
    id                            integer not null primary key autoincrement,
    provider_id                   integer not null,
    name                          varchar(120) not null,
    model_id                      varchar(200) not null,
    kind                          varchar(16) not null default 'CHAT',
    context_window                integer,
    max_output                    integer,
    parallel_tool_calls           boolean,
    temperature                   real,
    top_p                         real,
    top_k                         integer,
    min_p                         real,
    repeat_penalty                real,
    enabled                       boolean not null default true,
    token_limit                   integer,
    reset_interval                varchar(16) not null default 'MONTHLY',
    requests_per_minute           integer,
    throttle_tokens_per_second    bigint,
    throttle_requests_per_second  double precision,
    accept_retry_after            boolean,
    input_cost_per_million        numeric(12,4),
    output_cost_per_million       numeric(12,4),
    voice                         varchar(80),
    speech_skip_empty_lines       boolean not null default false,
    image_cost_per_image          numeric(12,4),
    reasoning_effort              varchar(16),
    cached_input_cost_per_million numeric(12,4),
    cache_write_cost_per_million  numeric(12,4),
    answer_limit_minutes          integer,
    takes_video                   boolean not null default false,
    takes_audio                   boolean not null default false,
    negative_prompt               text,
    constraint uk_llm_model_name UNIQUE (provider_id, name),
    constraint ck_llm_model_kind CHECK (((kind) IN ('CHAT', 'EMBEDDING', 'COMPLETION', 'TRANSCRIPTION', 'SPEECH', 'IMAGE', 'DECISION', 'UPSCALE', 'VIDEO'))),
    constraint ck_llm_model_reset CHECK (((reset_interval) IN ('DAILY', 'WEEKLY', 'MONTHLY', 'NEVER'))),
    constraint llm_model_provider_id_fkey FOREIGN KEY (provider_id) REFERENCES model_provider(id) ON DELETE CASCADE
);

INSERT INTO llm_model_new (id, provider_id, name, model_id, kind, context_window, max_output, parallel_tool_calls, temperature, top_p, top_k, min_p, repeat_penalty, enabled, token_limit, reset_interval, requests_per_minute, throttle_tokens_per_second, throttle_requests_per_second, accept_retry_after, input_cost_per_million, output_cost_per_million, voice, speech_skip_empty_lines, image_cost_per_image, reasoning_effort, cached_input_cost_per_million, cache_write_cost_per_million, answer_limit_minutes, takes_video, takes_audio, negative_prompt)
SELECT id, provider_id, name, model_id, kind, context_window, max_output, parallel_tool_calls, temperature, top_p, top_k, min_p, repeat_penalty, enabled, token_limit, reset_interval, requests_per_minute, throttle_tokens_per_second, throttle_requests_per_second, accept_retry_after, input_cost_per_million, output_cost_per_million, voice, speech_skip_empty_lines, image_cost_per_image, reasoning_effort, cached_input_cost_per_million, cache_write_cost_per_million, answer_limit_minutes, takes_video, takes_audio, negative_prompt FROM llm_model;

DROP TABLE llm_model;

ALTER TABLE llm_model_new RENAME TO llm_model;

CREATE INDEX idx_llm_model_provider ON llm_model (provider_id);

CREATE TABLE design_video (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    workspace_id        INTEGER       NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    model_id            INTEGER,
    model_name          VARCHAR(255)  NOT NULL,
    prompt              TEXT          NOT NULL,
    name                VARCHAR(255)  NOT NULL,
    seconds             INTEGER,
    size                VARCHAR(40),
    frames              INTEGER,
    fps                 INTEGER,
    width               INTEGER,
    height              INTEGER,
    seed                INTEGER,
    start_image_id      VARCHAR(80),
    requested_by        VARCHAR(255)  NOT NULL,
    requested_at        TIMESTAMP     NOT NULL,
    started_at          TIMESTAMP,
    finished_at         TIMESTAMP,
    status              VARCHAR(20)   NOT NULL,
    reason              TEXT,
    remote_id           VARCHAR(255),
    progress            INTEGER,
    filename            VARCHAR(255),
    content_type        VARCHAR(120),
    size_bytes          INTEGER,
    location            VARCHAR(1000),
    poster_location     VARCHAR(1000),
    poster_content_type VARCHAR(120),
    duration_seconds    DOUBLE PRECISION,
    CONSTRAINT ck_design_video_status CHECK (status IN ('QUEUED', 'GENERATING', 'DONE', 'FAILED', 'STOPPED'))
);

CREATE INDEX ix_design_video_workspace ON design_video (workspace_id, requested_at);
CREATE INDEX ix_design_video_status ON design_video (status);

RELEASE video_generation;

PRAGMA foreign_keys = ON;
