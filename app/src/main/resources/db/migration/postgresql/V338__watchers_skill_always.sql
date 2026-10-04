-- The watchers skill is Always for every agent, as it is for a new one.
--
-- An agent that has to decide to read the skill before setting a watcher
-- reaches for finish_answer with a wake-up instead, which is what Watchers was
-- built to replace. Appended to each agent's Always list, skipped where the
-- agent already marks it or has hidden it on purpose. Only adds rows.
INSERT INTO agent_required_skill (agent_id, position, name)
SELECT a.id,
       COALESCE((SELECT MAX(r.position) + 1 FROM agent_required_skill r WHERE r.agent_id = a.id), 0),
       'watchers'
FROM agent a
WHERE NOT EXISTS (SELECT 1 FROM agent_required_skill r WHERE r.agent_id = a.id AND r.name = 'watchers')
  AND NOT EXISTS (SELECT 1 FROM agent_hidden_skill h WHERE h.agent_id = a.id AND lower(h.name) = 'watchers');
