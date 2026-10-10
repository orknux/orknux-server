-- What a command run in a local tin session printed, kept on this side. The postgres
-- copy, V348, carries the reasoning.

CREATE TABLE tin_session_output
(
    id             integer not null primary key autoincrement,
    tin_session_id integer not null,
    execution_id   varchar(255) not null,
    start_offset   integer not null,
    text           text not null,
    truncated      boolean not null,
    constraint tin_session_output_tin_session_id_fkey FOREIGN KEY (tin_session_id) REFERENCES tin_session(id) ON DELETE CASCADE,
    constraint uk_tin_session_output_execution UNIQUE (tin_session_id, execution_id)
);

CREATE INDEX idx_tin_session_output_tin_session_id ON tin_session_output (tin_session_id);
