-- Postgres V407: a Workflow node, which runs another workflow of the
-- workspace and waits for it, and the run it starts recording which run called
-- it.
--
-- SQLite cannot change a CHECK in place, so the two tables whose CHECK names a
-- node kind are built again with the wider one - by SQLite's own procedure for
-- a table other tables point at
-- (https://sqlite.org/lang_altertable.html#otheralter): foreign keys off, so
-- dropping the old table does not cascade through what points at it; the new
-- one renamed into its place. Off has no effect inside a transaction, which is
-- why this script runs outside one (V85__workflow_node.sql.conf).

PRAGMA foreign_keys = OFF;

-- A savepoint rather than BEGIN, which Flyway's SQLite parser reads as the start of a trigger body.
SAVEPOINT workflow_node_kind;

CREATE TABLE workflow_node_new
(
    id                           integer not null primary key autoincrement,
    workflow_id                  integer not null,
    node_key                     varchar(64) not null,
    kind                         varchar(16) not null,
    name                         varchar(255) not null,
    description                  varchar(500),
    position_x                   float not null,
    position_y                   float not null,
    trigger_id                   integer,
    action_id                    integer,
    condition_id                 integer,
    agent_id                     integer,
    output_name                  varchar(60),
    icon                         varchar(40),
    object_id                    integer,
    image_model_id               integer,
    image_size                   varchar(16),
    image_quality                varchar(16),
    image_style                  varchar(16),
    decision_model_id            integer,
    decision_spec                text,
    output_object_id             integer,
    output_node_key              varchar(64),
    yes_label                    varchar(40),
    no_label                     varchar(40),
    orientation                  varchar(16),
    fallback_enabled             boolean not null default false,
    retry_attempts               integer,
    retry_backoff_seconds        integer,
    retry_multiplier             float,
    retry_max_wait_seconds       integer,
    retry_jitter                 float,
    retry_budget_seconds         integer,
    enabled                      boolean not null default true, stream_output BOOLEAN NOT NULL DEFAULT TRUE,
    called_workflow_id           integer,
    constraint uk_workflow_node UNIQUE (workflow_id, node_key),
    constraint ck_workflow_node_retry_multiplier CHECK (((retry_multiplier IS NULL) OR ((retry_multiplier >= 1) AND (retry_multiplier <= 10)))),
    constraint ck_workflow_node_retry_max_wait CHECK (((retry_max_wait_seconds IS NULL) OR ((retry_max_wait_seconds >= 1) AND (retry_max_wait_seconds <= 3600)))),
    constraint ck_workflow_node_retry_jitter CHECK (((retry_jitter IS NULL) OR ((retry_jitter >= 0) AND (retry_jitter <= 1)))),
    constraint ck_workflow_node_retry_budget CHECK (((retry_budget_seconds IS NULL) OR ((retry_budget_seconds >= 1) AND (retry_budget_seconds <= 86400)))),
    constraint ck_workflow_node_kind CHECK (((kind) IN ('TRIGGER', 'AGENT', 'ACTION', 'CONDITION', 'OBJECT', 'SESSION', 'IMAGE', 'DECISION', 'WORKFLOW'))),
    constraint workflow_node_action_id_fkey FOREIGN KEY (action_id) REFERENCES workflow_action(id) ON DELETE SET NULL,
    constraint workflow_node_agent_id_fkey FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE SET NULL,
    constraint workflow_node_condition_id_fkey FOREIGN KEY (condition_id) REFERENCES workflow_condition(id) ON DELETE SET NULL,
    constraint workflow_node_object_id_fkey FOREIGN KEY (object_id) REFERENCES workflow_object(id) ON DELETE SET NULL,
    constraint workflow_node_trigger_id_fkey FOREIGN KEY (trigger_id) REFERENCES workflow_trigger(id) ON DELETE SET NULL,
    constraint workflow_node_workflow_id_fkey FOREIGN KEY (workflow_id) REFERENCES workflow(id) ON DELETE CASCADE
);

INSERT INTO workflow_node_new (id, workflow_id, node_key, kind, name, description, position_x, position_y, trigger_id, action_id, condition_id, agent_id, output_name, icon, object_id, image_model_id, image_size, image_quality, image_style, decision_model_id, decision_spec, output_object_id, output_node_key, yes_label, no_label, orientation, fallback_enabled, retry_attempts, retry_backoff_seconds, retry_multiplier, retry_max_wait_seconds, retry_jitter, retry_budget_seconds, enabled, stream_output)
SELECT id, workflow_id, node_key, kind, name, description, position_x, position_y, trigger_id, action_id, condition_id, agent_id, output_name, icon, object_id, image_model_id, image_size, image_quality, image_style, decision_model_id, decision_spec, output_object_id, output_node_key, yes_label, no_label, orientation, fallback_enabled, retry_attempts, retry_backoff_seconds, retry_multiplier, retry_max_wait_seconds, retry_jitter, retry_budget_seconds, enabled, stream_output FROM workflow_node;

DROP TABLE workflow_node;

ALTER TABLE workflow_node_new RENAME TO workflow_node;

CREATE INDEX idx_workflow_node_action ON workflow_node (action_id) WHERE (action_id IS NOT NULL);

CREATE INDEX idx_workflow_node_agent ON workflow_node (agent_id);

CREATE INDEX idx_workflow_node_condition ON workflow_node (condition_id) WHERE (condition_id IS NOT NULL);

CREATE INDEX idx_workflow_node_trigger ON workflow_node (trigger_id) WHERE (trigger_id IS NOT NULL);

CREATE INDEX idx_workflow_node_workflow ON workflow_node (workflow_id);

CREATE INDEX idx_workflow_node_object_id ON workflow_node (object_id);

CREATE TABLE execution_step_new
(
    id                           integer not null primary key autoincrement,
    execution_id                 integer not null,
    node_key                     varchar(64) not null,
    kind                         varchar(16) not null,
    name                         varchar(255) not null,
    description                  varchar(500),
    status                       varchar(16) not null,
    position_x                   float not null,
    position_y                   float not null,
    step_order                   integer not null,
    started_at                   timestamp,
    finished_at                  timestamp,
    input                        text,
    output                       text,
    error                        varchar(1000),
    action_id                    integer,
    condition_id                 integer,
    wait_until                   timestamp,
    mappings                     text,
    agent_id                     integer,
    image_model_id               integer,
    image_size                   varchar(16),
    image_quality                varchar(16),
    image_style                  varchar(16),
    decision_model_id            integer,
    decision_spec                text,
    output_object_id             integer,
    output_name                  varchar(60),
    branch                       varchar(8),
    branch_option                varchar(64),
    carried_over                 boolean not null default false,
    retry_attempts               integer,
    retry_backoff_seconds        integer,
    retry_multiplier             float,
    retry_max_wait_seconds       integer,
    retry_jitter                 float,
    retry_budget_seconds         integer,
    retry_deadline               timestamp,
    attempts                     integer not null default 0,
    agent_sleeps                 integer not null default 0,
    agent_sleep_note             text,
    session_id                   integer,
    enabled                      boolean not null default true, agent_said TEXT, stream_output BOOLEAN NOT NULL DEFAULT FALSE,
    called_workflow_id           integer,
    child_execution_id           integer,
    constraint uk_execution_step UNIQUE (execution_id, node_key),
    constraint ck_execution_step_branch CHECK (((branch IS NULL) OR ((branch) IN ('YES', 'NO', 'FAILURE', 'OPTION', 'UNSURE')))),
    constraint ck_execution_step_kind CHECK (((kind) IN ('TRIGGER', 'AGENT', 'ACTION', 'CONDITION', 'OBJECT', 'IMAGE', 'DECISION', 'WORKFLOW'))),
    constraint ck_execution_step_status CHECK (((status) IN ('PENDING', 'RUNNING', 'WAITING', 'COMPLETED', 'FAILED', 'SKIPPED'))),
    constraint execution_step_execution_id_fkey FOREIGN KEY (execution_id) REFERENCES workflow_execution(id) ON DELETE CASCADE
);

INSERT INTO execution_step_new (id, execution_id, node_key, kind, name, description, status, position_x, position_y, step_order, started_at, finished_at, input, output, error, action_id, condition_id, wait_until, mappings, agent_id, image_model_id, image_size, image_quality, image_style, decision_model_id, decision_spec, output_object_id, output_name, branch, branch_option, carried_over, retry_attempts, retry_backoff_seconds, retry_multiplier, retry_max_wait_seconds, retry_jitter, retry_budget_seconds, retry_deadline, attempts, agent_sleeps, agent_sleep_note, session_id, enabled, agent_said, stream_output)
SELECT id, execution_id, node_key, kind, name, description, status, position_x, position_y, step_order, started_at, finished_at, input, output, error, action_id, condition_id, wait_until, mappings, agent_id, image_model_id, image_size, image_quality, image_style, decision_model_id, decision_spec, output_object_id, output_name, branch, branch_option, carried_over, retry_attempts, retry_backoff_seconds, retry_multiplier, retry_max_wait_seconds, retry_jitter, retry_budget_seconds, retry_deadline, attempts, agent_sleeps, agent_sleep_note, session_id, enabled, agent_said, stream_output FROM execution_step;

DROP TABLE execution_step;

ALTER TABLE execution_step_new RENAME TO execution_step;

CREATE INDEX idx_execution_step_execution ON execution_step (execution_id, step_order);

-- Which run called this one; stored as an API run, see the Postgres V407.
ALTER TABLE workflow_execution ADD COLUMN parent_execution_id INTEGER;
CREATE INDEX idx_workflow_execution_parent ON workflow_execution (parent_execution_id);

RELEASE workflow_node_kind;

PRAGMA foreign_keys = ON;
