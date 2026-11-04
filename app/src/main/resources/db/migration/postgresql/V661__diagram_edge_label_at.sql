-- Where along a routed line its label sits, as a share of the line's length,
-- where the router moved it clear of a box or another label; null is the
-- middle of its longest run. Nullable, so rollback-floor stays.
ALTER TABLE diagram_edge ADD COLUMN label_at DOUBLE PRECISION;
