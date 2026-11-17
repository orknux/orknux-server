-- How big a box's words are drawn, in pixels. Null is the size its kind is
-- drawn at, as every box was. Nullable, so rollback-floor stays.
ALTER TABLE diagram_node ADD COLUMN text_size INTEGER;
