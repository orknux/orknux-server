-- Which other agents an agent may put a question to.

-- An agent holding forty tools spends its context on forty descriptions and its
-- rounds on the chain of lookups one job needs. A specialist asked one question
-- does the looking up in a conversation of its own and hands back an answer, so
-- what reaches the agent that asked is the answer and not the working.

-- By id, like the connections beside it and for the same reason: an agent is
-- named by id everywhere a node or a trigger points at one, and a grant that
-- renamed itself out of meaning when somebody renamed an agent would be the trap
-- the by-name grants avoid by naming things a workspace renames rarely.
CREATE TABLE agent_agent
(
    agent_id  bigint  NOT NULL REFERENCES agent (id) ON DELETE CASCADE,
    position  integer NOT NULL,
    granted_id bigint NOT NULL,
    PRIMARY KEY (agent_id, position)
);
