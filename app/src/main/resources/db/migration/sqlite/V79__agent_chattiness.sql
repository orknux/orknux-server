-- How much an agent says while it works; see the Postgres V401.
ALTER TABLE agent ADD COLUMN chattiness VARCHAR(16) NOT NULL DEFAULT 'MEDIUM'
    CONSTRAINT ck_agent_chattiness CHECK (chattiness IN ('NONE', 'LOW', 'MEDIUM', 'HIGH'));
