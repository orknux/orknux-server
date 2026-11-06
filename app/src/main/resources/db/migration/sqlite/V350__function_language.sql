-- What a workspace function is written in. See the Postgres V670.

ALTER TABLE workflow_function
    ADD COLUMN language VARCHAR(20) NOT NULL DEFAULT 'TYPESCRIPT'
        CONSTRAINT ck_workflow_function_language CHECK (language IN ('TYPESCRIPT', 'PYTHON'));
