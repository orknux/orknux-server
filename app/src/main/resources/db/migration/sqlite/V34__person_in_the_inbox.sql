-- Postgres V356: a person's message in the inbox, and who wrote it.
--
-- session_event is built again for its wider CHECK, since SQLite cannot change
-- one in place - the way V15 did. Nothing points at session_event, so it is
-- copied, dropped and renamed, and its two indexes made again.

CREATE TABLE session_event_new
(
    id                           integer not null primary key autoincrement,
    session_id                   integer not null,
    kind                         varchar(16) not null,
    body                         text not null,
    due_at                       timestamp not null default CURRENT_TIMESTAMP,
    delivered_at                 timestamp,
    created_at                   timestamp not null default CURRENT_TIMESTAMP,
    said_by                      varchar(255),
    constraint session_event_session_id_fkey FOREIGN KEY (session_id) REFERENCES llm_session(id) ON DELETE CASCADE,
    constraint ck_session_event_kind CHECK (kind IN ('ANSWER', 'TIMER', 'WATCHER', 'PERSON'))
);

INSERT INTO session_event_new (id, session_id, kind, body, due_at, delivered_at, created_at)
SELECT id, session_id, kind, body, due_at, delivered_at, created_at FROM session_event;

DROP TABLE session_event;

ALTER TABLE session_event_new RENAME TO session_event;

CREATE INDEX ix_session_event_waiting ON session_event (session_id, due_at) WHERE delivered_at IS NULL;
CREATE INDEX ix_session_event_due ON session_event (due_at) WHERE delivered_at IS NULL;
