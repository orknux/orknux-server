-- A box's link: a web address, or another diagram as diagram:<id>, which a
-- reader opens from the box. Nullable, so rollback-floor stays.
ALTER TABLE diagram_node ADD COLUMN link TEXT;
