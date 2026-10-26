-- How a diagram's line finds its way between its nodes: straight, in right
-- angles, or curved. Nullable: a row written before is straight, as every line was.

ALTER TABLE diagram_edge ADD COLUMN routing VARCHAR(20);
