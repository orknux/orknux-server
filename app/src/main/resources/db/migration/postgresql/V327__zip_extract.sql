-- zip_extract: an archive unpacked into scratchpads. Issue #566.
--
-- Offered to every agent that has not hidden it (#455). Under a ceiling a
-- built-in not marked Always is found rather than carried; wherever zip_files
-- is Always, so is this, appended after the marks already there.

INSERT INTO agent_required_tool (agent_id, position, name)
SELECT r.agent_id,
       (SELECT max(m.position) FROM agent_required_tool m WHERE m.agent_id = r.agent_id) + 1,
       'zip_extract'
FROM agent_required_tool r
WHERE r.name = 'zip_files'
  AND NOT EXISTS (
      SELECT 1 FROM agent_required_tool e WHERE e.agent_id = r.agent_id AND e.name = 'zip_extract'
  );
