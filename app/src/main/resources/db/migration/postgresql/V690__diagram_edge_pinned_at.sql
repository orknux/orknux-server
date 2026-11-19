-- A line's end pinned to any point of its box's outline: where along its
-- pinned side it sits, as a share of the side. Null is the side's middle,
-- which is where every pinned end sat before. Nullable, so the rollback floor
-- stays.
ALTER TABLE diagram_edge ADD COLUMN from_at DOUBLE PRECISION;
ALTER TABLE diagram_edge ADD COLUMN to_at DOUBLE PRECISION;
