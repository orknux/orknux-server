-- A local tin session's event log, kept on this side.
--
-- The local tin keeps its sessions in memory, so a restart of this server took
-- every local session's log with it and the conversation's Tin panel had
-- nothing to show. Only the local tin's: a remote tin is a server of its own
-- that keeps its sessions itself, and is read live. The events are copied here as they are seen - after a tool
-- call on the session, before tin_close stops it, and whenever the panel reads
-- the log - and the panel reads them from here.
--
-- One row per event, keyed by the tin's own sequence number, which is unique
-- within the session. `at` is kept as the tin wrote it rather than parsed, so
-- what the panel shows is what the tin said. execution.output events are not
-- copied: the panel never asked for them, and they are the bulk of a log.
-- The rows go with their tin_session, which goes with its conversation and its
-- workspace.
--
-- Additive - one new table - so rollback-floor stays where it is.

CREATE TABLE tin_session_event
(
    id             BIGSERIAL PRIMARY KEY,
    tin_session_id BIGINT       NOT NULL REFERENCES tin_session (id) ON DELETE CASCADE,
    seq            BIGINT       NOT NULL,
    at             VARCHAR(64)  NOT NULL,
    type           VARCHAR(255) NOT NULL,
    data           TEXT         NOT NULL,
    CONSTRAINT uk_tin_session_event_seq UNIQUE (tin_session_id, seq)
);

CREATE INDEX idx_tin_session_event_tin_session_id ON tin_session_event (tin_session_id);
