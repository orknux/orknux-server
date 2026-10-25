-- A document's history, kept in component_revision as the other kinds' is.
--
-- Which door a save came through - the editor, an import, the document chat,
-- the orknux_* tools, a restore - so the history can say how a version was
-- made and not only by whom. Null on every revision written before this, which
-- did not record it. Additive.
ALTER TABLE component_revision ADD COLUMN door VARCHAR(16);

-- The door the document's live state was saved through, written into the
-- revision the next save displaces it into. Null on documents not saved since.
ALTER TABLE design_document ADD COLUMN updated_via VARCHAR(16);

-- The document chat's turn that last saved it, so that the saves one turn makes
-- one after another keep one version - the state before the turn - rather than
-- one per tool call. Null for every other door.
ALTER TABLE design_document ADD COLUMN updated_turn VARCHAR(64);
