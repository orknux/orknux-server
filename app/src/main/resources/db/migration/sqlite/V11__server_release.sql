-- Server jars this installation holds, to start from or to roll back to. #584.
-- The postgres copy, V333, carries the reasoning. A file of its own rather than
-- folded into the baseline, like V9 and V10: SQLite installations exist now,
-- and editing a migration they have run changes its checksum and refuses their
-- next start.

CREATE TABLE server_release
(
    id               integer not null primary key autoincrement,
    version          varchar(64) not null,
    sha256           varchar(64) not null,
    size             integer not null,
    schema_version   integer not null,
    schema_floor     integer not null,
    source           varchar(16) not null,
    state            varchar(16) not null,
    boot_attempts    integer not null default 0,
    fallback_id      integer,
    image_version    varchar(64),
    failure          varchar(500),
    failure_reported boolean not null default true,
    stored_at        timestamp not null,
    stored_by        varchar(120) not null,
    activated_at     timestamp,
    activated_by     varchar(120),
    booted_at        timestamp,
    constraint uk_server_release_sha256 UNIQUE (sha256),
    constraint ck_server_release_source CHECK (source IN ('ORKNUX_AI', 'UPLOAD')),
    constraint ck_server_release_state CHECK (state IN ('STORED', 'ACTIVATING', 'ACTIVE', 'FAILED'))
);

CREATE TABLE server_release_part
(
    release_id integer not null,
    part       integer not null,
    bytes      blob not null,
    primary key (release_id, part),
    constraint server_release_part_release_id_fkey FOREIGN KEY (release_id) REFERENCES server_release(id) ON DELETE CASCADE
);
