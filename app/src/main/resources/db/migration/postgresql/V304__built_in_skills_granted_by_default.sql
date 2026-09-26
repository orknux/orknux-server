-- The skills the server brings itself are granted, not waited for. Issue #471.
--
-- The built-in catalog `orknux_skills` was offered the way a plugin's is, which
-- means an agent held it only where somebody had gone and ticked it. Nobody had.
-- So every command those skills exist for - the marker and an id, the one piece
-- of syntax a person has with an agent - resolved to nothing on the list side:
-- `skill_list` came back empty, the briefing wrote no skills paragraph, and an
-- agent asked what commands it takes had nothing to answer from. The command
-- itself still loaded the skill, because an id is resolved across the workspace
-- and its plugins rather than against the grant (#381), and that asymmetry is
-- what made it look like a listing bug rather than a missing grant.
--
-- Same reasoning as V303 for the built-in tools: what the server brings is on
-- unless somebody turned it off. Stored as a grant rather than as a hidden list,
-- because there is one built-in catalog and skills arrive inside it - a skill
-- added in a later release reaches every agent holding the catalog with no
-- migration at all - and because the opt-out is then the checkbox already on the
-- agent's page rather than a row nobody can see.
--
-- At the end of each agent's list, since `position` is an @OrderColumn and the
-- names before it are somebody's ordering.

INSERT INTO agent_skill_catalog (agent_id, position, name)
SELECT a.id,
       coalesce((SELECT max(s.position) + 1 FROM agent_skill_catalog s WHERE s.agent_id = a.id), 0),
       'orknux_skills'
FROM agent a
WHERE NOT EXISTS (
    SELECT 1 FROM agent_skill_catalog s WHERE s.agent_id = a.id AND s.name = 'orknux_skills'
);
