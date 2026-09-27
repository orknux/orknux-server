-- agent_list: who an agent may ask, with what each holds. Issue #552.
--
-- A built-in nobody has hidden is offered (#455), so no agent needs the name
-- granted. Under a ceiling, though, a built-in not marked Always is found
-- rather than carried - and this one belongs beside ask_agent, which is what
-- it is for. So wherever ask_agent is marked Always, so is agent_list,
-- appended after the marks the agent already has.

INSERT INTO agent_required_tool (agent_id, position, name)
SELECT r.agent_id,
       (SELECT max(m.position) FROM agent_required_tool m WHERE m.agent_id = r.agent_id) + 1,
       'agent_list'
FROM agent_required_tool r
WHERE r.name = 'ask_agent'
  AND NOT EXISTS (
      SELECT 1 FROM agent_required_tool e WHERE e.agent_id = r.agent_id AND e.name = 'agent_list'
  );
