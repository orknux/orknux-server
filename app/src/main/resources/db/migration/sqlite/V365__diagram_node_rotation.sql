-- How far a box is turned about its middle, in degrees clockwise. Postgres V685.
ALTER TABLE diagram_node ADD COLUMN rotation INTEGER;
