-- What a person writes in a chat while its agent is answering is left in the
-- session's inbox, and the turn reads it between rounds like anything else
-- that arrives. A kind of its own, because it is the person speaking rather than
-- the server reporting, and the name of who wrote it, for the transcript.
--
-- Additive: a wider CHECK and a nullable column; an older jar writes neither.

ALTER TABLE session_event ADD COLUMN said_by VARCHAR(255);

ALTER TABLE session_event DROP CONSTRAINT ck_session_event_kind;
ALTER TABLE session_event
    ADD CONSTRAINT ck_session_event_kind CHECK (kind IN ('ANSWER', 'TIMER', 'WATCHER', 'PERSON'));
