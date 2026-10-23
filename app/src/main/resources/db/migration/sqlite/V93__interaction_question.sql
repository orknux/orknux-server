-- See the Postgres V415: a question's multiple choice and own-words answer, and ask_person marked Always.
ALTER TABLE interaction_request ADD COLUMN multiple BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE interaction_request ADD COLUMN allows_text BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE interaction_request ADD COLUMN answer_text TEXT;

INSERT INTO agent_required_tool (agent_id, position, name)
SELECT a.id,
       coalesce((SELECT max(r.position) FROM agent_required_tool r WHERE r.agent_id = a.id), -1) + 1,
       'ask_person'
FROM agent a
JOIN workspace w ON w.id = a.workspace_id
WHERE (a.max_tools IS NULL OR w.unsafe_built_in_tools = 0)
  AND NOT EXISTS (SELECT 1 FROM agent_required_tool r WHERE r.agent_id = a.id AND r.name = 'ask_person')
  AND NOT EXISTS (SELECT 1 FROM agent_hidden_tool h WHERE h.agent_id = a.id AND h.name = 'ask_person');
