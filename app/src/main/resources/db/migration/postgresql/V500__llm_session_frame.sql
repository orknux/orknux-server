-- What a session's requests carry in front of their messages - the system turn
-- and the tool array - as first sent, so every later request sends them again
-- byte for byte.
--
-- Measured on llama.cpp with a hybrid model: one tool added to the array, or one
-- line added to the system turn, and the whole prompt was read again, where a
-- request with a message added read 18 tokens. A grant accepted in a long chat
-- re-read 167,792 tokens. llm_session_prompt (V349) keeps the messages; this
-- keeps what comes before them. What changes later goes in as a message, and
-- the frame is made again only for another agent or model, an agent edited
-- elsewhere, or a prompt rewritten by a compaction.
--
-- Additive: an older jar never reads it, and a session without a row is framed
-- on its next turn.
CREATE TABLE llm_session_frame (
    session_id BIGINT PRIMARY KEY REFERENCES llm_session (id) ON DELETE CASCADE,
    agent_id BIGINT NOT NULL,
    model_id BIGINT NOT NULL,
    -- The agent's last-modified time in epoch seconds when framed, or when a
    -- grant made in this conversation was taken in.
    agent_stamp BIGINT NOT NULL,
    system_turn TEXT,
    -- The array as JSON: every field a request is built from.
    tools TEXT NOT NULL,
    hunting BOOLEAN NOT NULL DEFAULT FALSE,
    -- What the agent was last told it is told and holds, for saying what changed.
    seen_system TEXT,
    seen_tools TEXT NOT NULL DEFAULT '',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
