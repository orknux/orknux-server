-- A question put to an editor's chat, kept until it is answered. The same as Postgres V682. Additive.
CREATE TABLE editor_ask
(
    id          INTEGER      PRIMARY KEY AUTOINCREMENT,
    user_id     varchar(120) NOT NULL,
    kind        varchar(16)  NOT NULL,
    item_id     INTEGER      NOT NULL,
    status      varchar(16)  NOT NULL DEFAULT 'WAITING',
    result      text,
    error       text,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    beat_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMP,
    CONSTRAINT ck_editor_ask_status CHECK (status IN ('WAITING', 'ANSWERED', 'FAILED'))
);
CREATE INDEX idx_editor_ask_item ON editor_ask (user_id, kind, item_id);
