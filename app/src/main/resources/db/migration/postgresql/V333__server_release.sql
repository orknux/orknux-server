-- Server jars this installation holds, to start from or to roll back to. #584.
--
-- An administrator presses Update, or uploads a jar, and what arrives is kept
-- here rather than on a disk: the container's filesystem is the image's and is
-- thrown away on every restart, and the database is the one thing every replica
-- already shares. The launcher in the image reads the chosen one at start-up,
-- checks its signature against the certificate the image carries, writes it out
-- and runs it.
--
-- The bytes are in server_release_part, in pieces, rather than in one column.
-- A jar is a third of a gigabyte, Postgres caps a bytea at one, and pgjdbc reads
-- a bytea whole into memory - so a single column would hold a launcher's heap
-- hostage to the size of a release. A piece at a time keeps both bounded.
--
-- schema_version is the newest migration the jar carries and schema_floor the
-- newest one it cannot be rolled back past (db/migration/rollback-floor); both
-- are read out of the jar when it is stored, never typed in. A rollback is
-- refused once the database has run a release whose floor is above the jar's
-- schema.

CREATE TABLE server_release (
    id             BIGSERIAL PRIMARY KEY,
    version        VARCHAR(64)  NOT NULL,
    sha256         VARCHAR(64)  NOT NULL,
    size           BIGINT       NOT NULL,
    schema_version INTEGER      NOT NULL,
    schema_floor   INTEGER      NOT NULL,
    source         VARCHAR(16)  NOT NULL,
    state          VARCHAR(16)  NOT NULL,
    boot_attempts  INTEGER      NOT NULL DEFAULT 0,
    -- What was running when this one was activated, to go back to if it never
    -- starts. No foreign key: pruning keeps it, and a dangling id reads as
    -- "the image's own jar", which is the right answer anyway.
    fallback_id    BIGINT,
    -- The image's version when this was activated. An image newer than that is
    -- a platform team's upgrade, and wins over what the database holds.
    image_version  VARCHAR(64),
    failure        VARCHAR(500),
    failure_reported BOOLEAN    NOT NULL DEFAULT TRUE,
    stored_at      TIMESTAMPTZ  NOT NULL,
    stored_by      VARCHAR(120) NOT NULL,
    activated_at   TIMESTAMPTZ,
    activated_by   VARCHAR(120),
    booted_at      TIMESTAMPTZ,
    CONSTRAINT uk_server_release_sha256 UNIQUE (sha256),
    CONSTRAINT ck_server_release_source CHECK (source IN ('ORKNUX_AI', 'UPLOAD')),
    CONSTRAINT ck_server_release_state CHECK (state IN ('STORED', 'ACTIVATING', 'ACTIVE', 'FAILED'))
);

CREATE TABLE server_release_part (
    release_id BIGINT  NOT NULL REFERENCES server_release (id) ON DELETE CASCADE,
    part       INTEGER NOT NULL,
    bytes      BYTEA   NOT NULL,
    PRIMARY KEY (release_id, part)
);
