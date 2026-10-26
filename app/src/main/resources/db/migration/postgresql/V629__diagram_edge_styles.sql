-- A diagram's line has a style along its length and a mark at each end, where
-- it had one of four kinds. Nullable: a row written before is read off its kind,
-- which every row keeps as the nearest of the four for a reader that knows only them.

ALTER TABLE diagram_edge ADD COLUMN line_style VARCHAR(20);
ALTER TABLE diagram_edge ADD COLUMN start_end VARCHAR(20);
ALTER TABLE diagram_edge ADD COLUMN end_end VARCHAR(20);
