-- Where a box's words sit inside it; null is where its kind puts them.
ALTER TABLE diagram_node ADD COLUMN text_align VARCHAR(8);
ALTER TABLE diagram_node ADD COLUMN text_valign VARCHAR(8);
