-- A working file an agent keeps within one session: a mutable document it
-- writes to a piece at a time, reads back in fragments, and deletes when done.
-- Distinct from a note (a short line handed back whole) and from the session
-- store (a key-value map for tools). Issue #411.
CREATE TABLE session_scratchpad
(
    id          bigserial PRIMARY KEY,
    session_id  bigint       NOT NULL REFERENCES llm_session (id) ON DELETE CASCADE,
    -- Its name within the session, the way the agent addresses it. One per name.
    name        varchar(200) NOT NULL,
    -- What it is for, so a list of them reads without opening each.
    description varchar(500),
    content     text         NOT NULL DEFAULT '',
    -- Whether the sessions started under this one may read and add to it.
    shared      boolean      NOT NULL DEFAULT false,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uk_session_scratchpad UNIQUE (session_id, name)
);

CREATE INDEX idx_session_scratchpad_session ON session_scratchpad (session_id);
