-- A local tin session's event log, kept on this side. The postgres copy, V347,
-- carries the reasoning.

CREATE TABLE tin_session_event
(
    id             integer not null primary key autoincrement,
    tin_session_id integer not null,
    seq            integer not null,
    at             varchar(64) not null,
    type           varchar(255) not null,
    data           text not null,
    constraint tin_session_event_tin_session_id_fkey FOREIGN KEY (tin_session_id) REFERENCES tin_session(id) ON DELETE CASCADE,
    constraint uk_tin_session_event_seq UNIQUE (tin_session_id, seq)
);

CREATE INDEX idx_tin_session_event_tin_session_id ON tin_session_event (tin_session_id);
