-- A question an agent puts to the person with ask_person: options that each
-- carry a description, any number of them chosen where `multiple` says so,
-- and the person's own words where `allows_text` lets them answer otherwise.
-- ask_person is a built-in beside tools_request, marked Always for the agents
-- that predate it, as V413 did remove_session_rating. Additive.
ALTER TABLE interaction_request ADD COLUMN multiple BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE interaction_request ADD COLUMN allows_text BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE interaction_request ADD COLUMN answer_text TEXT;

INSERT INTO agent_required_tool (agent_id, position, name)
SELECT a.id,
       coalesce((SELECT max(r.position) FROM agent_required_tool r WHERE r.agent_id = a.id), -1) + 1,
       'ask_person'
FROM agent a
JOIN workspace w ON w.id = a.workspace_id
WHERE (a.max_tools IS NULL OR w.unsafe_built_in_tools = false)
  AND NOT EXISTS (SELECT 1 FROM agent_required_tool r WHERE r.agent_id = a.id AND r.name = 'ask_person')
  AND NOT EXISTS (SELECT 1 FROM agent_hidden_tool h WHERE h.agent_id = a.id AND h.name = 'ask_person');
