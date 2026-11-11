-- Chat turns that outlive the process answering them. Additive: two new tables
-- an older jar does not read.
--
-- A row in chat_turn says a chat's answer is being written, and beat_at is how
-- recently the process writing it said it was still there. A row whose beat has
-- gone quiet is a turn a restart or a crash cut off, and the sweep answers it
-- again. Deleted when the answer is kept or the turn ends any other way.
CREATE TABLE chat_turn
(
    chat_id    bigint      PRIMARY KEY,
    started_at timestamptz NOT NULL DEFAULT now(),
    beat_at    timestamptz NOT NULL DEFAULT now(),
    -- How many times it has been picked up again, so one that keeps taking the
    -- server down with it is given up on.
    resumes    integer     NOT NULL DEFAULT 0
);
CREATE INDEX idx_chat_turn_beat ON chat_turn (beat_at);

-- A question put to the quick chat, answered on the server rather than inside
-- the request that asked it, so the answer survives the page closing and the
-- server restarting. The newest rows of a person in a workspace are also what
-- the panel draws again when it is opened.
CREATE TABLE quick_chat_ask
(
    id           bigserial    PRIMARY KEY,
    workspace_id bigint       NOT NULL,
    user_id      varchar(255) NOT NULL,
    -- The conversation as the panel sent it, the question last: JSON.
    messages     text         NOT NULL,
    page_label   varchar(500),
    page_path    varchar(2000),
    answered_by  varchar(32),
    status       varchar(16)  NOT NULL,
    -- What answered it, as JSON: the answer and any change it offers.
    result       text,
    error        text,
    cleared      boolean      NOT NULL DEFAULT false,
    resumes      integer      NOT NULL DEFAULT 0,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    beat_at      timestamptz  NOT NULL DEFAULT now(),
    finished_at  timestamptz,
    CONSTRAINT ck_quick_chat_ask_status CHECK (status IN ('WAITING', 'ANSWERED', 'FAILED', 'STOPPED'))
);
CREATE INDEX idx_quick_chat_ask_person ON quick_chat_ask (workspace_id, user_id, id);
CREATE INDEX idx_quick_chat_ask_waiting ON quick_chat_ask (status, beat_at);
