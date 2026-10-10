-- What a command run in a local tin session printed, kept on this side once it has
-- finished - for the same reason as its log, V347: the local tin keeps its
-- sessions in memory, and a restart of this server took every command's output
-- with it.
--
-- One row per finished execution, the combined stream, up to the Admin
-- setting tin.local.output.kept.bytes. The local tin's only, as with V347. start_offset is where in the stream the text
-- starts, which is not 0 where the tin had already let the head of a long
-- output go; truncated says the text stops before the stream did.
--
-- Additive - one new table - so rollback-floor stays where it is.

CREATE TABLE tin_session_output
(
    id             BIGSERIAL PRIMARY KEY,
    tin_session_id BIGINT       NOT NULL REFERENCES tin_session (id) ON DELETE CASCADE,
    execution_id   VARCHAR(255) NOT NULL,
    start_offset   BIGINT       NOT NULL,
    text           TEXT         NOT NULL,
    truncated      BOOLEAN      NOT NULL,
    CONSTRAINT uk_tin_session_output_execution UNIQUE (tin_session_id, execution_id)
);

CREATE INDEX idx_tin_session_output_tin_session_id ON tin_session_output (tin_session_id);
