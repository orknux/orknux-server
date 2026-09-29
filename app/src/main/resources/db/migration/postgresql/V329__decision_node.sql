-- A decision model: Jev, TypeSafe's hosted one, or Laya running on the
-- installation's own hardware. Both answer POST /v1/systemone - a state and a
-- set of typed questions in, calibrated probabilities out - so one provider type
-- covers both, and a model under it is a DECISION model. Issue #577.
ALTER TABLE model_provider DROP CONSTRAINT ck_model_provider_type;
ALTER TABLE model_provider
    ADD CONSTRAINT ck_model_provider_type
        CHECK (type IN ('OPENAI', 'ANTHROPIC', 'AZURE_OPENAI', 'OLLAMA', 'SYSTEM_ONE'));

ALTER TABLE llm_model DROP CONSTRAINT ck_llm_model_kind;
ALTER TABLE llm_model
    ADD CONSTRAINT ck_llm_model_kind CHECK (
        kind IN ('CHAT', 'EMBEDDING', 'COMPLETION', 'TRANSCRIPTION', 'SPEECH', 'IMAGE', 'DECISION')
    );

-- The node that asks one. It picks its model on itself, the way an image node
-- does, and holds its questions and threshold as one JSON document: a question
-- carries a list of options or levels, and nothing ever asks the database about
-- a single one. The run keeps its own copy of both, like every other id a node
-- instances.
ALTER TABLE workflow_node ADD COLUMN decision_model_id BIGINT;
ALTER TABLE workflow_node ADD COLUMN decision_spec TEXT;
ALTER TABLE execution_step ADD COLUMN decision_model_id BIGINT;
ALTER TABLE execution_step ADD COLUMN decision_spec TEXT;

ALTER TABLE workflow_node DROP CONSTRAINT ck_workflow_node_kind;
ALTER TABLE workflow_node ADD CONSTRAINT ck_workflow_node_kind CHECK (
    kind IN ('TRIGGER', 'AGENT', 'ACTION', 'CONDITION', 'OBJECT', 'SESSION', 'IMAGE', 'DECISION')
);

ALTER TABLE execution_step DROP CONSTRAINT ck_execution_step_kind;
ALTER TABLE execution_step ADD CONSTRAINT ck_execution_step_kind CHECK (
    kind IN ('TRIGGER', 'AGENT', 'ACTION', 'CONDITION', 'OBJECT', 'IMAGE', 'DECISION')
);

-- A choice question branches the graph one edge per option, and an answer under
-- the node's threshold leaves by an edge of its own. The branch says which kind
-- of way out it is; the option says which one, since options are the node's to
-- name rather than a fixed pair.
ALTER TABLE workflow_edge ADD COLUMN branch_option VARCHAR(64);
ALTER TABLE execution_step ADD COLUMN branch_option VARCHAR(64);

ALTER TABLE execution_step DROP CONSTRAINT ck_execution_step_branch;
ALTER TABLE execution_step
    ADD CONSTRAINT ck_execution_step_branch
        CHECK (branch IS NULL OR branch IN ('YES', 'NO', 'FAILURE', 'OPTION', 'UNSURE'));
