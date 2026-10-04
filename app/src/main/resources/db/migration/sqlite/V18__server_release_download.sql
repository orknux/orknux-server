-- A server jar on its way in, #602. The postgres copy, V340, carries the
-- reasoning.

CREATE TABLE server_release_download
(
    id               integer not null primary key autoincrement,
    source           varchar(16) not null,
    version          varchar(64),
    host             varchar(255) not null,
    source_url       varchar(2000) not null,
    state            varchar(16) not null,
    received         integer not null default 0,
    total            integer,
    bytes_per_second integer not null default 0,
    attempts         integer not null default 0,
    resumed          integer not null default 0,
    failed_in_row    integer not null default 0,
    next_attempt_at  timestamp,
    last_error       varchar(500),
    activate         boolean not null,
    release_id       integer,
    failure          varchar(1000),
    started_by       varchar(120) not null,
    started_at       timestamp not null,
    updated_at       timestamp not null,
    finished_at      timestamp,
    dismissed        boolean not null default false,
    constraint ck_server_release_download_source CHECK (source IN ('ORKNUX_AI', 'URL')),
    constraint ck_server_release_download_state CHECK (
        state IN ('DOWNLOADING', 'WAITING', 'VERIFYING', 'STORING', 'RESTARTING', 'DONE', 'FAILED')
    )
);

CREATE INDEX ix_server_release_download_started ON server_release_download (started_at);
