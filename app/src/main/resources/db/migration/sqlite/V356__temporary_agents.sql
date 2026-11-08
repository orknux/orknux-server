-- Temporary agents, and the fourth tool visibility, Delegate. The same as
-- Postgres V676. Additive only.
CREATE TABLE agent_delegated_tool
(
    agent_id integer      not null,
    position integer      not null,
    name     varchar(255) not null,
    primary key (agent_id, position),
    constraint agent_delegated_tool_agent_id_fkey FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE
);

CREATE TABLE agent_creatable_model
(
    agent_id integer not null,
    position integer not null,
    model_id integer not null,
    primary key (agent_id, position),
    constraint agent_creatable_model_agent_id_fkey FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE
);

ALTER TABLE agent ADD COLUMN temporary boolean NOT NULL DEFAULT false;
ALTER TABLE agent ADD COLUMN created_by_session_id integer;
ALTER TABLE agent ADD COLUMN created_by_agent_id integer;
CREATE INDEX idx_agent_created_by_session ON agent (created_by_session_id);
