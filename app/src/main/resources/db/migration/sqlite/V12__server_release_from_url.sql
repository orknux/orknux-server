-- A server release fetched from a URL, #589. The postgres copy, V334, carries
-- the reasoning.
--
-- SQLite cannot change a CHECK in place, so the table is built again. The
-- parts are copied first and their old table dropped before the releases':
-- foreign keys are on, and dropping server_release while server_release_part
-- still pointed at it would delete every stored jar on the way out.
-- ALTER TABLE ... RENAME rewrites the new parts' reference to the releases'
-- final name, which is what puts the two back together.

CREATE TABLE server_release_new
(
    id               integer not null primary key autoincrement,
    version          varchar(64) not null,
    sha256           varchar(64) not null,
    size             integer not null,
    schema_version   integer not null,
    schema_floor     integer not null,
    source           varchar(16) not null,
    source_url       varchar(2000),
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
    constraint ck_server_release_source CHECK (source IN ('ORKNUX_AI', 'UPLOAD', 'URL')),
    constraint ck_server_release_state CHECK (state IN ('STORED', 'ACTIVATING', 'ACTIVE', 'FAILED'))
);

INSERT INTO server_release_new (id, version, sha256, size, schema_version, schema_floor, source, state,
                                boot_attempts, fallback_id, image_version, failure, failure_reported,
                                stored_at, stored_by, activated_at, activated_by, booted_at)
SELECT id, version, sha256, size, schema_version, schema_floor, source, state,
       boot_attempts, fallback_id, image_version, failure, failure_reported,
       stored_at, stored_by, activated_at, activated_by, booted_at
FROM server_release;

CREATE TABLE server_release_part_new
(
    release_id integer not null,
    part       integer not null,
    bytes      blob not null,
    primary key (release_id, part),
    constraint server_release_part_release_id_fkey FOREIGN KEY (release_id) REFERENCES server_release_new(id) ON DELETE CASCADE
);

INSERT INTO server_release_part_new (release_id, part, bytes)
SELECT release_id, part, bytes FROM server_release_part;

DROP TABLE server_release_part;
DROP TABLE server_release;

ALTER TABLE server_release_new RENAME TO server_release;
ALTER TABLE server_release_part_new RENAME TO server_release_part;
