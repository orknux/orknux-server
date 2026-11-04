-- Where along a routed line its label sits. Postgres V661.
ALTER TABLE diagram_edge ADD COLUMN label_at REAL;
