-- How far a box is turned about its middle, in whole degrees clockwise.
-- Null is upright, as every box was drawn. Nullable, so rollback-floor stays.
ALTER TABLE diagram_node ADD COLUMN rotation INTEGER;
