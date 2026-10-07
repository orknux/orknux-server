-- A watcher may wake its agent to look at the result itself, on an interval of
-- its own, while the condition has not matched; the agent can then change the
-- watcher with watcher_update or end it. Two nullable columns, so a watcher set
-- before this simply never asks. Issue #618.
ALTER TABLE watcher ADD COLUMN agent_check_interval_seconds integer;
ALTER TABLE watcher ADD COLUMN next_agent_check_at timestamptz;

-- watcher_update is a built-in beside the other three, and is given the Always
-- mark V337 gave them, on the same terms.
INSERT INTO agent_required_tool (agent_id, position, name)
SELECT a.id,
       coalesce((SELECT max(r.position) FROM agent_required_tool r WHERE r.agent_id = a.id), -1) + 1,
       'watcher_update'
FROM agent a
JOIN workspace w ON w.id = a.workspace_id
WHERE (a.max_tools IS NULL OR w.unsafe_built_in_tools = false)
  AND NOT EXISTS (SELECT 1 FROM agent_required_tool r WHERE r.agent_id = a.id AND r.name = 'watcher_update')
  AND NOT EXISTS (SELECT 1 FROM agent_hidden_tool h WHERE h.agent_id = a.id AND h.name = 'watcher_update');
