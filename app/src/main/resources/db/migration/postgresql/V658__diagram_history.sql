-- A diagram's history, kept in component_revision as a document's is, under
-- the kind DIAGRAM. component_revision.kind has no CHECK, so the new kind needs
-- nothing there.
--
-- Which door the diagram's live state was saved through - the editor, its chat,
-- the orknux_* tools, a restore - written into the revision the next save
-- displaces it into. Null on diagrams not saved since.
ALTER TABLE diagram ADD COLUMN updated_via VARCHAR(16);

-- The diagram chat's turn that last saved it, so the saves one turn makes one
-- after another keep one version - the state before the turn - rather than one
-- per tool call. Null for every other door. Both nullable: rollback-floor stays.
ALTER TABLE diagram ADD COLUMN updated_turn VARCHAR(64);
