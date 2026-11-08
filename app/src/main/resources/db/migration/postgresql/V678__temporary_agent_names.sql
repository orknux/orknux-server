-- A temporary agent's name is its conversation's, not the workspace's: two
-- conversations may each make a "web-fetcher", and one may share a name with an
-- agent of the workspace. The workspace's agents stay unique by name among
-- themselves; a conversation's temporary agents are unique among themselves.
--
-- Additive for a rollback: an older jar never makes two of a name, and reads a
-- temporary agent by its session.
ALTER TABLE agent DROP CONSTRAINT uk_agent_workspace_name;
CREATE UNIQUE INDEX uk_agent_workspace_name ON agent (workspace_id, name) WHERE NOT temporary;
CREATE UNIQUE INDEX uk_agent_session_name ON agent (created_by_session_id, name) WHERE temporary;
