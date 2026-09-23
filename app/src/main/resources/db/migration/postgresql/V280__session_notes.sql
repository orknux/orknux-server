-- What an agent wrote down for itself, part-way through.

-- Issue #371. An agent that wakes from a wait is handed the note it left, which
-- covers the moment it parks. What it had no way to do is write something down
-- while it is still working: the findings of the first six steps of a long job,
-- the thing it must not forget to do at the end, the reason it ruled an approach
-- out.

-- The transcript is not that. It is trimmed to fit a share of the context window
-- - `memoryShare` decides how much comes back - so what an agent said twenty
-- turns ago is exactly what is gone by the time it matters. A note is the thing
-- that must not fall out, so it is kept apart and handed back whole.

-- On the session rather than the step, because that is the span an agent thinks
-- across: a workflow node keyed to a session shares it with every other node
-- that computes the same key, and a chat is one conversation over days.
CREATE TABLE llm_session_note
(
    id         bigserial PRIMARY KEY,
    session_id bigint      NOT NULL REFERENCES llm_session (id) ON DELETE CASCADE,
    note       text        NOT NULL,
    -- Which agent wrote it. A session can be shared, and "somebody decided this"
    -- is worth less than knowing which of them did.
    written_by varchar(200) NOT NULL,
    written_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX llm_session_note_session_idx ON llm_session_note (session_id, written_at, id);
