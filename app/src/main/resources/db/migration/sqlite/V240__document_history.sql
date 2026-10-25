-- A document's history and which door each save came through; see the Postgres history's V560.
ALTER TABLE component_revision ADD COLUMN door VARCHAR(16);
ALTER TABLE design_document ADD COLUMN updated_via VARCHAR(16);
ALTER TABLE design_document ADD COLUMN updated_turn VARCHAR(64);
