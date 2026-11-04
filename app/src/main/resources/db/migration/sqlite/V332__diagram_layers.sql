-- Layers in a diagram. Postgres V652.
ALTER TABLE diagram ADD COLUMN layers TEXT;
ALTER TABLE diagram_node ADD COLUMN layer_key VARCHAR(64);
ALTER TABLE diagram_edge ADD COLUMN layer_key VARCHAR(64);
