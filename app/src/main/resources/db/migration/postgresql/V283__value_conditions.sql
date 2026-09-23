-- A condition about a value from the run, checked against a list. Issue #378.

-- The general form of the typed conditions: a Slack condition knows where a
-- message's author is, this one is told by the node it sits on - the same
-- reference picker a node's parameters use, under the argument name `value`.
-- So it has a check and values and no property, which is a shape the
-- constraint did not have a branch for.
ALTER TABLE workflow_condition DROP CONSTRAINT IF EXISTS ck_workflow_condition_type;
ALTER TABLE workflow_condition ADD CONSTRAINT ck_workflow_condition_type
    CHECK (type IN ('SLACK', 'JIRA', 'TIME', 'FUNCTION', 'ANY_OF', 'ALL_OF', 'VALUE'));

ALTER TABLE workflow_condition DROP CONSTRAINT IF EXISTS ck_workflow_condition_shape;
ALTER TABLE workflow_condition ADD CONSTRAINT ck_workflow_condition_shape CHECK (
    workflow_id IS NOT NULL OR (
        (type IN ('ANY_OF', 'ALL_OF') AND property IS NULL AND check_by IS NULL) OR
        (type = 'FUNCTION' AND function_id IS NOT NULL AND property IS NULL AND check_by IS NULL) OR
        (type IN ('SLACK', 'JIRA', 'TIME') AND property IS NOT NULL AND check_by IS NOT NULL) OR
        (type = 'VALUE' AND property IS NULL AND check_by IS NOT NULL)
    )
);
