-- Widgets: small live things an agent adds to a chat and keeps up to date -
-- a timer, a stopwatch - drawn above the composer. One row per widget, its
-- kind's state as JSON, because each kind keeps a different shape and a new
-- kind should be a handler, not a migration. The kinds are a CHECK, widened as
-- kinds are added. A chat's widgets go with it.
-- Additive: an older jar does not know the table.

CREATE TABLE chat_widget (
    id              BIGSERIAL PRIMARY KEY,
    chat_session_id BIGINT NOT NULL REFERENCES chat_session (id) ON DELETE CASCADE,
    kind            VARCHAR(32) NOT NULL,
    label           VARCHAR(200) NOT NULL,
    state           TEXT NOT NULL,
    created_by      VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_chat_widget_kind CHECK (kind IN ('TIMER', 'STOPWATCH'))
);

CREATE INDEX idx_chat_widget_chat ON chat_widget (chat_session_id);
