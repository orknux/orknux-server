-- Every workspace has one memory catalog that is always there.

-- A catalog is what an agent is granted and what a memory is filed into, and a
-- workspace that has none has nowhere for either: `memory_save` refuses because
-- there is nothing to write to, and the two memory tools are not offered at all.
-- So the first thing anybody has to do before an agent can remember anything is
-- a piece of setup nobody is told about.

-- This one cannot be deleted, so the floor cannot be taken away again. It can be
-- renamed, which is what makes "General" a starting point rather than a name
-- somebody is stuck with - and it is why this is a flag and not a name: a
-- catalog renamed to "What the desk knows" is still the one that stays.
ALTER TABLE memory_catalog ADD COLUMN is_default boolean NOT NULL DEFAULT false;

-- One for every workspace that has no catalog by that name, and the existing one
-- promoted where the name is already taken - inserting would fail the unique
-- constraint, and a workspace that already calls a catalog General meant that
-- one.
INSERT INTO memory_catalog (workspace_id, name, created_at, created_by, is_default)
SELECT w.id, 'General', now(), 'system', true
FROM workspace w
WHERE NOT EXISTS (
    SELECT 1 FROM memory_catalog c WHERE c.workspace_id = w.id AND c.name = 'General'
);

UPDATE memory_catalog SET is_default = true WHERE name = 'General' AND is_default = false;
