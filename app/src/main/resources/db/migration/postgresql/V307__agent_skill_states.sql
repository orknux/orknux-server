-- A skill is Offered, Hidden or Always, the way a tool is. Issue #480.
--
-- The catalogs an agent holds say what is in scope; these two lists say what
-- happens to each skill inside them. Hidden takes one out of the list and out
-- of reach of skill_load. Always puts its whole page in the system turn every
-- turn, for instructions that are not "read this when it applies" but "this is
-- how you work here".
--
-- The exception is stored rather than the rule, as V303 stores it for the
-- built-in tools: a skill added to a granted catalog next month arrives offered
-- rather than switched off because nobody went back and ticked it. Both tables
-- start empty, so every agent keeps exactly the behaviour it has today.

CREATE TABLE agent_hidden_skill
(
    agent_id bigint       NOT NULL REFERENCES agent (id) ON DELETE CASCADE,
    position integer      NOT NULL,
    name     varchar(255) NOT NULL,
    PRIMARY KEY (agent_id, position)
);

CREATE TABLE agent_required_skill
(
    agent_id bigint       NOT NULL REFERENCES agent (id) ON DELETE CASCADE,
    position integer      NOT NULL,
    name     varchar(255) NOT NULL,
    PRIMARY KEY (agent_id, position)
);
