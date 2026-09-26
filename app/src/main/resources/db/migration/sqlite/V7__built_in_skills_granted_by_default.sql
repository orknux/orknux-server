-- The skills the server brings itself are granted, not waited for. Issue #471.
-- The postgres copy, V304, carries the reasoning.

INSERT INTO agent_skill_catalog (agent_id, position, name)
SELECT a.id,
       coalesce((SELECT max(s.position) + 1 FROM agent_skill_catalog s WHERE s.agent_id = a.id), 0),
       'orknux_skills'
FROM agent a
WHERE NOT EXISTS (
    SELECT 1 FROM agent_skill_catalog s WHERE s.agent_id = a.id AND s.name = 'orknux_skills'
);
