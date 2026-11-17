-- Where a box's words sit inside it: across (left, center, right) and down
-- (top, middle, bottom). Null is where its kind puts them, as every box was
-- drawn. Nullable, so rollback-floor stays.
ALTER TABLE diagram_node ADD COLUMN text_align VARCHAR(8);
ALTER TABLE diagram_node ADD COLUMN text_valign VARCHAR(8);
