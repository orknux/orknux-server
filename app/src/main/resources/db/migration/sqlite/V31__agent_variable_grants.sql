-- Which variable catalogs an agent may use, and which secrets a box session's
-- commands were given. The postgres copy, V353, carries the reasoning.

CREATE TABLE agent_secret_catalog
(
    agent_id                     integer not null,
    position                     integer not null,
    name                         varchar(120) not null,
    primary key (agent_id, position),
    constraint agent_secret_catalog_agent_id_fkey FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE
);

CREATE TABLE agent_variable_catalog
(
    agent_id                     integer not null,
    position                     integer not null,
    name                         varchar(120) not null,
    primary key (agent_id, position),
    constraint agent_variable_catalog_agent_id_fkey FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE
);

ALTER TABLE tin_session ADD COLUMN secret_variable_ids TEXT;
