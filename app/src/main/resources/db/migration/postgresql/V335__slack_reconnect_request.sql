-- Somebody pressing Reconnect on a Slack connection. #592.
--
-- A Socket Mode socket belongs to the process that opened it, and a press
-- reaches one replica of however many there are, so it is written down here
-- and every replica's listener compares it with the session it holds. A
-- generation rather than a time: each press adds one, a session remembers the
-- generation it was opened under, and a newer one is a press it has not
-- honoured - with no replica's clock compared against another's.
--
-- Additive: an older jar has no entity for this table and leaves it alone.

CREATE TABLE slack_reconnect_request (
    connection_id BIGINT      PRIMARY KEY REFERENCES workspace_connection (id) ON DELETE CASCADE,
    generation    BIGINT      NOT NULL,
    requested_at  TIMESTAMPTZ NOT NULL
);
