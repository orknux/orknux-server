-- What a tool is written in: TypeScript, as every tool has been, or Python.
-- See the Postgres V669 for why; the same column, spelled for SQLite.

ALTER TABLE agent_tool
    ADD COLUMN language VARCHAR(20) NOT NULL DEFAULT 'TYPESCRIPT'
        CONSTRAINT ck_agent_tool_language CHECK (language IN ('TYPESCRIPT', 'PYTHON'));
