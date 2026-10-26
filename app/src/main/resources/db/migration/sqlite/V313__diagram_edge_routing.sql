-- Postgres V633: how a diagram's line finds its way between its nodes.

ALTER TABLE diagram_edge ADD COLUMN routing VARCHAR(20);
