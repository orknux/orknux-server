-- What a workspace function is written in: TypeScript, as every function has
-- been, or Python - the same choice a tool has had since V669.
--
-- A Python function holds the same text in `source` and `typescript`, since
-- nothing compiles it. Additive with a default, so an older jar on this schema
-- reads every function it knew the same way.

ALTER TABLE workflow_function
    ADD COLUMN language VARCHAR(20) NOT NULL DEFAULT 'TYPESCRIPT'
        CONSTRAINT ck_workflow_function_language CHECK (language IN ('TYPESCRIPT', 'PYTHON'));
