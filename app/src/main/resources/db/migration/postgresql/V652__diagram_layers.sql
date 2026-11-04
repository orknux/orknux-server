-- Layers in a diagram, as draw.io has them: the diagram holds them in order,
-- each with a name and whether it is shown and locked, as JSON - a short list
-- read and written whole with the drawing - and each box and line names the
-- one it is on. Null on all three is the one layer every diagram had before.
-- Nullable, so rollback-floor stays where it is.
ALTER TABLE diagram ADD COLUMN layers TEXT;
ALTER TABLE diagram_node ADD COLUMN layer_key VARCHAR(64);
ALTER TABLE diagram_edge ADD COLUMN layer_key VARCHAR(64);
