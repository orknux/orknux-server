-- What a tool is written in: TypeScript, as every tool has been, or Python.
--
-- A Python tool keeps the same text in `source` and `typescript`, because
-- nothing compiles it: the editor shows what runs. Additive, with a default, so
-- an older jar rolled back onto this schema reads every tool it knew about the
-- same way and simply does not see the column.

ALTER TABLE agent_tool
    ADD COLUMN language VARCHAR(20) NOT NULL DEFAULT 'TYPESCRIPT'
        CONSTRAINT ck_agent_tool_language CHECK (language IN ('TYPESCRIPT', 'PYTHON'));
