-- A skill has an id, and a workspace marks its commands. Issue #381.
--
-- The id is what a workflow graph names a skill by, and what a word in a
-- Slack message becomes: `!review` loads the skill whose id is `review`.
-- Letters, underscores and hyphens, unique in the workspace - so a marker
-- followed by a word is always a whole id and never half of one.
--
-- Every existing skill gets one derived from its name with everything else
-- removed; two names that come to the same id are told apart with a letter,
-- `review` and `review-b`, because digits are not allowed. A name with
-- nothing left - "2024" - becomes `skill`.
ALTER TABLE agent_skill
    ADD COLUMN skill_key VARCHAR(120) NOT NULL DEFAULT '';

UPDATE agent_skill
SET skill_key = COALESCE(NULLIF(LEFT(regexp_replace(name, '[^A-Za-z_-]', '', 'g'), 120), ''), 'skill');

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

ALTER TABLE agent_skill
    ALTER COLUMN skill_key DROP DEFAULT;

CREATE UNIQUE INDEX uk_agent_skill_key ON agent_skill (workspace_id, LOWER(skill_key));

-- What marks a command in a message that starts a run in this workspace.
-- Orknux's own rather than Slack's `/`, which Slack intercepts. `!` to start.
ALTER TABLE workspace
    ADD COLUMN command_marker VARCHAR(3) NOT NULL DEFAULT '!';
