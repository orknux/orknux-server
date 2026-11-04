-- Outline and line colours and thicknesses. Postgres V657.
ALTER TABLE diagram_node ADD COLUMN border_color VARCHAR(7);
ALTER TABLE diagram_node ADD COLUMN border_width INTEGER;
ALTER TABLE diagram_edge ADD COLUMN color VARCHAR(7);
ALTER TABLE diagram_edge ADD COLUMN width INTEGER;
