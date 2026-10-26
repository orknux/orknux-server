-- Postgres V629: a diagram line's style along its length and the mark at each end.

ALTER TABLE diagram_edge ADD COLUMN line_style VARCHAR(20);
ALTER TABLE diagram_edge ADD COLUMN start_end VARCHAR(20);
ALTER TABLE diagram_edge ADD COLUMN end_end VARCHAR(20);
