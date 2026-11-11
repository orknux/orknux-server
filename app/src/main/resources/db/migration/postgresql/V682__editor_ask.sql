-- A question put to an editor's chat, kept until it is answered, so a reload
-- or a dropped connection does not lose the answer. One per person and item:
-- asking again forgets the one before. Additive.
CREATE TABLE editor_ask
(
    id          BIGSERIAL PRIMARY KEY,
    user_id     varchar(120) NOT NULL,
    kind        varchar(16)  NOT NULL,
    item_id     BIGINT       NOT NULL,
    status      varchar(16)  NOT NULL DEFAULT 'WAITING',
    result      text,
    error       text,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    beat_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    CONSTRAINT ck_editor_ask_status CHECK (status IN ('WAITING', 'ANSWERED', 'FAILED'))
);
CREATE INDEX idx_editor_ask_item ON editor_ask (user_id, kind, item_id);
