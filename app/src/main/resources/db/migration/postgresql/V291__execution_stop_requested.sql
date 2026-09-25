-- Whether a run has been asked to stop, and the terminal state that results.
-- The engine reads the flag before each step and ends the run where it stands;
-- a STOPPED run does not resume. Issue #395.
ALTER TABLE workflow_execution ADD COLUMN stop_requested boolean NOT NULL DEFAULT false;

ALTER TABLE workflow_execution DROP CONSTRAINT ck_execution_status;
ALTER TABLE workflow_execution ADD CONSTRAINT ck_execution_status
    CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED', 'STOPPED'));
