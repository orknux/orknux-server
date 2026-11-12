-- A line's two ends can be pinned to a side of their box, where somebody dragged
-- them there. Null is the floating end every line had: the side facing the other
-- box, found as the boxes move. Additive, so an older jar still reads the table.
ALTER TABLE diagram_edge ADD COLUMN from_side TEXT;
ALTER TABLE diagram_edge ADD COLUMN to_side TEXT;
