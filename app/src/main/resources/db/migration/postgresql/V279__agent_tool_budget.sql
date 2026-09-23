-- How many tools an agent carries, and which of them always travel.

-- Tool search (#368) starts where the provider's own ceiling is reached, which
-- is 128 for OpenAI and Azure. That is the number at which a request *fails*,
-- not the number at which an agent starts choosing badly - a model handed
-- eighty tools is already picking from a list it cannot hold in mind, and the
-- context they occupy is paid for on every round of every turn.

-- So an agent can be given a smaller one. Null is the provider's, which is how
-- every agent has worked until now.
ALTER TABLE agent ADD COLUMN max_tools integer;

-- And which of the granted tools are not up for negotiation.
--
-- A tool an agent uses constantly should not have to be found: an agent that
-- spends a round rediscovering the one thing it does every time is an agent
-- paying the cost of the search without the benefit. Everything granted and not
-- named here is loaded when it is looked for and dropped again when the room is
-- wanted for something else.
--
-- By name, like the grant it qualifies, and for the same reason: a workspace
-- tool is granted by name, so marking one has to be too or the two would come
-- apart the first time anybody renamed anything.
CREATE TABLE agent_required_tool
(
    agent_id bigint  NOT NULL REFERENCES agent (id) ON DELETE CASCADE,
    position integer NOT NULL,
    name     varchar(255) NOT NULL,
    PRIMARY KEY (agent_id, position)
);
