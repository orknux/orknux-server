-- A skill's id keeps its words. Issue #435.
--
-- V286 derived an id by deleting everything the rule refuses, so "Answering in
-- a thread" became `Answeringinathread` - one long word nobody can spell from
-- memory, which is how a model asked to load it ends up guessing at shapes like
-- `plugin::skill` instead. A run of refused characters becomes a hyphen
-- instead, and the id is lowercased: `answering-in-a-thread`.
--
-- Nothing is stranded by the rewrite. A skill is looked up by its exact id
-- first and then on its letters alone, so a graph or a command written against
-- the old id names the same skill as before.

UPDATE agent_skill
SET skill_key = COALESCE(
    NULLIF(
        LEFT(TRIM(BOTH '-' FROM LOWER(regexp_replace(name, '[^A-Za-z_-]+', '-', 'g'))), 120),
        ''
    ),
    'skill'
);

-- Two names that now come to the same id are told apart the way V286 told them
-- apart: with a letter, because the rule allows no digits.
WITH ranked AS (
    SELECT id,
           skill_key,
           ROW_NUMBER() OVER (PARTITION BY workspace_id, LOWER(skill_key) ORDER BY id) AS place
    FROM agent_skill
)
UPDATE agent_skill s
SET skill_key = LEFT(r.skill_key, 118) || '-' || CHR((95 + r.place)::int)
FROM ranked r
WHERE s.id = r.id AND r.place > 1;
