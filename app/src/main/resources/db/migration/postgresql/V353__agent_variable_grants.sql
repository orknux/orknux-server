-- Which variable catalogs an agent may use: the secrets of some, handed to a
-- box command as environment variables and masked wherever its output is
-- shown, and the plain values of others, which it may read. By name, the way
-- agent_memory_catalog is.
--
-- And which secrets a box session's commands were given, by variable id and
-- never by value, so what the box printed can be masked with them.
--
-- Additive: an older jar does not know the tables or the column.

CREATE TABLE agent_secret_catalog (
    agent_id BIGINT       NOT NULL REFERENCES agent (id) ON DELETE CASCADE,
    position INTEGER      NOT NULL,
    name     VARCHAR(120) NOT NULL,
    PRIMARY KEY (agent_id, position)
);

CREATE TABLE agent_variable_catalog (
    agent_id BIGINT       NOT NULL REFERENCES agent (id) ON DELETE CASCADE,
    position INTEGER      NOT NULL,
    name     VARCHAR(120) NOT NULL,
    PRIMARY KEY (agent_id, position)
);

ALTER TABLE tin_session ADD COLUMN secret_variable_ids TEXT;
