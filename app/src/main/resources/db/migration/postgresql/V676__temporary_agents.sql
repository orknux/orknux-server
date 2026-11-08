-- Temporary agents, and the fourth tool visibility, Delegate. Additive only:
-- two new tables and three columns an older jar does not read.

-- Tools an agent holds only to hand on to an agent it creates: never offered
-- to the agent itself. By name, like agent_required_tool beside it; a name is
-- in at most one of this and agent_granted_tool.
CREATE TABLE agent_delegated_tool
(
    agent_id bigint       NOT NULL REFERENCES agent (id) ON DELETE CASCADE,
    position integer      NOT NULL,
    name     varchar(255) NOT NULL,
    PRIMARY KEY (agent_id, position)
);

-- Which models an agent may create temporary agents with, by id. None is an
-- agent that cannot create agents, which is every agent before this.
CREATE TABLE agent_creatable_model
(
    agent_id bigint  NOT NULL REFERENCES agent (id) ON DELETE CASCADE,
    position integer NOT NULL,
    model_id bigint  NOT NULL,
    PRIMARY KEY (agent_id, position)
);

-- An agent made by another agent inside one conversation, for that
-- conversation: hidden from every list, removed when the conversation goes
-- idle. The session and the creator are kept by id without a foreign key -
-- a temporary agent outliving either is swept, not cascaded.
ALTER TABLE agent ADD COLUMN temporary boolean NOT NULL DEFAULT false;
ALTER TABLE agent ADD COLUMN created_by_session_id bigint;
ALTER TABLE agent ADD COLUMN created_by_agent_id bigint;
CREATE INDEX idx_agent_created_by_session ON agent (created_by_session_id);
