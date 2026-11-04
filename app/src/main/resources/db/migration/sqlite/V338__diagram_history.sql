-- A diagram's history: which door saved it, and the chat turn that did. Postgres V658.
ALTER TABLE diagram ADD COLUMN updated_via VARCHAR(16);
ALTER TABLE diagram ADD COLUMN updated_turn VARCHAR(64);
