-- See the Postgres V410: one row per thing that failed, for Admin > Reports >
-- Errors, with the runs that had already failed read in.
CREATE TABLE error_record (
    id           INTEGER PRIMARY KEY,
    at           TIMESTAMP     NOT NULL,
    workspace_id BIGINT        NOT NULL,
    source       VARCHAR(16)   NOT NULL,
    subject      VARCHAR(255)  NOT NULL,
    execution_id BIGINT,
    session_id   BIGINT,
    message      TEXT          NOT NULL
);

CREATE INDEX idx_error_record_at ON error_record (at);

INSERT INTO error_record (at, workspace_id, source, subject, execution_id, message)
SELECT coalesce(e.finished_at, e.started_at), e.workspace_id, 'RUN', e.workflow_name, e.id,
       coalesce(e.error, 'The run failed.')
FROM workflow_execution e
WHERE e.status = 'FAILED';
