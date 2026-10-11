-- Which message of its chat a file came with, by its place in the thread - the
-- same keying the thinking and the earlier takes of an answer use.
--
-- A chat drew every file it held in one strip above the composer, because
-- nothing said which message a file belonged to. A file a person sent goes
-- under their message; one the agent's side filed during a turn goes under the
-- answer of that turn.
--
-- Null for every row that predates this: those are placed by when they were
-- uploaded. Additive: an older jar does not know the column.

ALTER TABLE chat_attachment ADD COLUMN message_index INTEGER;
