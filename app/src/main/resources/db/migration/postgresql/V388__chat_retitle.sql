-- Whether a chat's title was given by a model, which is the only kind ever
-- named again, and the exchanges finished since it was. A chat that predates
-- this keeps its title: nobody knows any more whether a person typed it.
-- Additive, so an older jar still starts.

ALTER TABLE chat_session ADD COLUMN title_generated BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE chat_session ADD COLUMN turns_since_title INTEGER NOT NULL DEFAULT 0;
