-- Postgres V690: where along its pinned side a line's end sits. Nullable.
ALTER TABLE diagram_edge ADD COLUMN from_at REAL;
ALTER TABLE diagram_edge ADD COLUMN to_at REAL;
