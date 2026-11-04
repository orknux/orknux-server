-- A line's own corners, where it was routed around the boxes in its way rather
-- than drawn the one way its routing names: whole-pixel points, "x,y x,y",
-- start to end, without its two ends, which stay on the boxes so a box moved
-- carries them. Null is a line that has none. Nullable, so rollback-floor stays.
ALTER TABLE diagram_edge ADD COLUMN points TEXT;
