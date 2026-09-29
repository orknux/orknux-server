-- finish_answer is carried on every turn wherever the workspace keeps its
-- built-ins fixed. The postgres copy, V330, carries the reasoning.

INSERT INTO agent_required_tool (agent_id, position, name)
SELECT a.id,
       coalesce((SELECT max(r.position) FROM agent_required_tool r WHERE r.agent_id = a.id), -1) + 1,
       'finish_answer'
FROM agent a
JOIN workspace w ON w.id = a.workspace_id
WHERE w.unsafe_built_in_tools = 0
  AND NOT EXISTS (
      SELECT 1 FROM agent_required_tool r WHERE r.agent_id = a.id AND r.name = 'finish_answer'
  );
