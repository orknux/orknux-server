-- How much an agent says while it works, between its tool calls.
--
-- Every agent was told the same thing: say what you are about to do, and every
-- few calls what you found. Some people want an agent that only answers, some
-- want one that narrates. MEDIUM is what every agent was told until now, so an
-- existing agent says exactly what it said before. A workflow node may override
-- it with its own `chattiness` parameter.
ALTER TABLE agent ADD COLUMN chattiness VARCHAR(16) NOT NULL DEFAULT 'MEDIUM'
    CONSTRAINT ck_agent_chattiness CHECK (chattiness IN ('NONE', 'LOW', 'MEDIUM', 'HIGH'));
