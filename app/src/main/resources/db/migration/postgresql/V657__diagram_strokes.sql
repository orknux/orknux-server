-- How a box's outline and a line are drawn: a colour, #rrggbb, and a
-- thickness in pixels. Null on all four is drawn as before. Nullable, so
-- rollback-floor stays.
ALTER TABLE diagram_node ADD COLUMN border_color VARCHAR(7);
ALTER TABLE diagram_node ADD COLUMN border_width INTEGER;
ALTER TABLE diagram_edge ADD COLUMN color VARCHAR(7);
ALTER TABLE diagram_edge ADD COLUMN width INTEGER;
