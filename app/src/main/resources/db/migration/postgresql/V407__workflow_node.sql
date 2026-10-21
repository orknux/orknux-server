-- A Workflow node: runs another workflow of the same workspace, hands it what
-- the node's parameters say, waits for it to finish and passes on what it
-- produced. The node names the workflow by id, like every catalogue entry a
-- node points at; the run keeps its own copy, and the run it starts keeps
-- which step started it.
ALTER TABLE workflow_node ADD COLUMN called_workflow_id BIGINT;
ALTER TABLE execution_step ADD COLUMN called_workflow_id BIGINT;
ALTER TABLE execution_step ADD COLUMN child_execution_id BIGINT;

-- Which run called this one, where a Workflow node did. Such a run is stored as
-- an API one rather than under a trigger type of its own, which an older jar
-- could not read after a rollback; this column is what says who called it. No
-- foreign key: a run is history, and deleting the caller's record should not
-- change the callee's.
ALTER TABLE workflow_execution ADD COLUMN parent_execution_id BIGINT;
CREATE INDEX idx_workflow_execution_parent ON workflow_execution (parent_execution_id);

ALTER TABLE workflow_node DROP CONSTRAINT ck_workflow_node_kind;
ALTER TABLE workflow_node ADD CONSTRAINT ck_workflow_node_kind CHECK (
    kind IN ('TRIGGER', 'AGENT', 'ACTION', 'CONDITION', 'OBJECT', 'SESSION', 'IMAGE', 'DECISION', 'WORKFLOW')
);

ALTER TABLE execution_step DROP CONSTRAINT ck_execution_step_kind;
ALTER TABLE execution_step ADD CONSTRAINT ck_execution_step_kind CHECK (
    kind IN ('TRIGGER', 'AGENT', 'ACTION', 'CONDITION', 'OBJECT', 'IMAGE', 'DECISION', 'WORKFLOW')
);
