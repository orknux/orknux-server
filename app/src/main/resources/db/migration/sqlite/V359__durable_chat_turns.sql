-- Chat turns that outlive the process answering them. The same as Postgres
-- V679. Additive.
CREATE TABLE chat_turn
(
    chat_id    INTEGER   PRIMARY KEY,
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    beat_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resumes    integer   NOT NULL DEFAULT 0
);
CREATE INDEX idx_chat_turn_beat ON chat_turn (beat_at);

CREATE TABLE quick_chat_ask
(
    id           INTEGER      PRIMARY KEY AUTOINCREMENT,
    workspace_id BIGINT       NOT NULL,
    user_id      varchar(255) NOT NULL,
    messages     text         NOT NULL,
    page_label   varchar(500),
    page_path    varchar(2000),
    answered_by  varchar(32),
    status       varchar(16)  NOT NULL,
    result       text,
    error        text,
    cleared      boolean      NOT NULL DEFAULT false,
    resumes      integer      NOT NULL DEFAULT 0,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    beat_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at  TIMESTAMP,
    CONSTRAINT ck_quick_chat_ask_status CHECK (status IN ('WAITING', 'ANSWERED', 'FAILED', 'STOPPED'))
);
CREATE INDEX idx_quick_chat_ask_person ON quick_chat_ask (workspace_id, user_id, id);
CREATE INDEX idx_quick_chat_ask_waiting ON quick_chat_ask (status, beat_at);
