-- Somebody pressing Reconnect on a Slack connection. #592. The postgres copy,
-- V334, carries the reasoning. A file of its own rather than folded into the
-- baseline, like V9 to V11: SQLite installations exist now, and editing a
-- migration they have run changes its checksum and refuses their next start.

CREATE TABLE slack_reconnect_request
(
    connection_id integer not null primary key,
    generation    integer not null,
    requested_at  timestamp not null,
    constraint slack_reconnect_request_connection_id_fkey FOREIGN KEY (connection_id) REFERENCES workspace_connection (id) ON DELETE CASCADE
);
