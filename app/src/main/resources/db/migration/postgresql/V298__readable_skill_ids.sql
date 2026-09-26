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
--
-- Derived and told apart in one statement, because the unique index is checked
-- as each row is written: "Answering in a thread" and "Answering in a thread
-- (2)" come to the same id, and a second statement to separate them would run
-- after the first had already been refused. Two that land together are told
-- apart with a letter, the way V286 told them apart, because the rule allows no
-- digits.

WITH derived AS (
    SELECT id,
           workspace_id,
           COALESCE(
               NULLIF(
                   TRIM(BOTH '-' FROM LEFT(
                       TRIM(BOTH '-' FROM LOWER(regexp_replace(name, '[^A-Za-z_-]+', '-', 'g'))),
                       120
                   )),
                   ''
               ),
               'skill'
           ) AS base
    FROM agent_skill
),
ranked AS (
    SELECT id,
           base,
           ROW_NUMBER() OVER (PARTITION BY workspace_id, base ORDER BY id) AS place
    FROM derived
)
UPDATE agent_skill s
SET skill_key = CASE
        WHEN r.place = 1 THEN r.base
        ELSE LEFT(r.base, 118) || '-' || CHR((95 + r.place)::int)
    END
FROM ranked r
WHERE s.id = r.id;
