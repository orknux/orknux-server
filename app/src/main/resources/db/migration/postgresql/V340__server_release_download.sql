-- A server jar on its way in, #602.
--
-- Update (the official server) and Fetch (a URL) used to download, verify and
-- store a third of a gigabyte inside the administrator's one GraphQL request,
-- so any proxy in front of the server that cuts a long request - nginx and an
-- Ingress at sixty seconds of silence, Cloudflare at a hundred - dropped the
-- browser's connection, and a download that broke half way started from
-- nothing. Now the request starts a download the server runs by itself and
-- answers at once, and this row is where it stands: what the page polls, what
-- it comes back to after a reload, and what another replica sees.
--
-- received, total and bytes_per_second are written by the replica doing the
-- work about once a second, which is also its heartbeat: a row in a working
-- state that has not been touched for longer than a connection may go silent
-- belongs to a server that stopped, and is reported as such. attempts counts
-- connections, resumed those that continued from bytes already held (HTTP
-- Range) rather than starting again, and failed_in_row the broken ones in a
-- row that brought nothing, which is what the give-up limit is counted in.
--
-- The credential a URL may need is held in memory by the replica downloading,
-- and never here; source_url is written without it, its query or fragment.
--
-- Additive: a new table, so rollback-floor stays where it is.

CREATE TABLE server_release_download (
    id               BIGSERIAL PRIMARY KEY,
    source           VARCHAR(16)   NOT NULL,
    -- The version the official server listed, or the one read from a URL's jar
    -- once it is verified; null until it is known.
    version          VARCHAR(64),
    host             VARCHAR(255)  NOT NULL,
    source_url       VARCHAR(2000) NOT NULL,
    state            VARCHAR(16)   NOT NULL,
    received         BIGINT        NOT NULL DEFAULT 0,
    total            BIGINT,
    bytes_per_second BIGINT        NOT NULL DEFAULT 0,
    attempts         INTEGER       NOT NULL DEFAULT 0,
    resumed          INTEGER       NOT NULL DEFAULT 0,
    failed_in_row    INTEGER       NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ,
    -- Why the last connection broke, while it is being retried.
    last_error       VARCHAR(500),
    -- Whether the release is started once stored: Update does, Fetch does not.
    activate         BOOLEAN       NOT NULL,
    -- The release it became. No foreign key: a release can be pruned, and the
    -- record of having fetched it is still true.
    release_id       BIGINT,
    failure          VARCHAR(1000),
    started_by       VARCHAR(120)  NOT NULL,
    started_at       TIMESTAMPTZ   NOT NULL,
    updated_at       TIMESTAMPTZ   NOT NULL,
    finished_at      TIMESTAMPTZ,
    -- Put away by an administrator, so the page stops showing how it ended.
    dismissed        BOOLEAN       NOT NULL DEFAULT FALSE,
    CONSTRAINT ck_server_release_download_source CHECK (source IN ('ORKNUX_AI', 'URL')),
    CONSTRAINT ck_server_release_download_state CHECK (
        state IN ('DOWNLOADING', 'WAITING', 'VERIFYING', 'STORING', 'RESTARTING', 'DONE', 'FAILED')
    )
);

CREATE INDEX ix_server_release_download_started ON server_release_download (started_at);
