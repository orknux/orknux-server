-- A diagram's node and edge labels lose their 500-character cap: a note shape
-- holds its text in its label, and a long note was refused on save.
--
-- Widening only, so the rollback floor stays: an older jar reads TEXT as the
-- VARCHAR it expects. SQLite needs nothing - it never enforced the length.
ALTER TABLE diagram_node ALTER COLUMN label TYPE TEXT;
ALTER TABLE diagram_edge ALTER COLUMN label TYPE TEXT;
