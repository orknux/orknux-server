-- Widgets an agent adds to a chat: a timer, a stopwatch. See the Postgres V359.

CREATE TABLE chat_widget
(
    id              integer not null primary key autoincrement,
    chat_session_id integer not null,
    kind            varchar(32) not null,
    label           varchar(200) not null,
    state           text not null,
    created_by      varchar(255) not null,
    created_at      timestamp not null default CURRENT_TIMESTAMP,
    updated_at      timestamp not null default CURRENT_TIMESTAMP,
    constraint ck_chat_widget_kind CHECK (kind IN ('TIMER', 'STOPWATCH')),
    constraint chat_widget_chat_session_id_fkey FOREIGN KEY (chat_session_id) REFERENCES chat_session(id) ON DELETE CASCADE
);

CREATE INDEX idx_chat_widget_chat ON chat_widget (chat_session_id);
