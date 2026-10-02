-- A run started by an event on a connection is recorded as CONNECTION rather
-- than WEBHOOK. The postgres copy, V334, carries the reasoning; old rows keep
-- WEBHOOK.
--
-- SQLite cannot alter a CHECK, so the table is rebuilt the way the SQLite
-- documentation's "other kinds of table schema changes" lays out: a new table
-- with the wider constraint, every row copied, the old one dropped and the new
-- one renamed into its place.
--
-- **Outside a transaction, with foreign keys off, and on purpose.** Every
-- connection here opens with foreign_keys on (SqliteConfig), and with it on, a
-- DROP TABLE first deletes every row - which ON DELETE CASCADE carries into
-- execution_step, execution_log, execution_picture and execution_speech, so the
-- rebuild would empty the history of every run it was meant to keep. The pragma
-- is a no-op inside a transaction, which is why V12__...sql.conf turns
-- Flyway's off for this one file; the SAVEPOINT and RELEASE below put the rebuild
-- itself back inside one, so it lands whole or not at all.

PRAGMA foreign_keys = OFF;

SAVEPOINT rebuild_workflow_execution;

CREATE TABLE workflow_execution_rebuilt
(
    id                           integer not null primary key autoincrement,
    workspace_id                 integer not null,
    workflow_id                  integer not null,
    workflow_name                varchar(255) not null,
    status                       varchar(16) not null,
    trigger_type                 varchar(16) not null,
    started_at                   timestamp not null,
    finished_at                  timestamp,
    input                        text,
    error                        varchar(1000),
    stopped_at_node_key          varchar(64),
    stopped_reason               varchar(500),
    carried                      text,
    started_from                 integer,
    fired_trigger_id             integer,
    stop_requested               boolean not null default false,
    constraint ck_execution_status CHECK (((status) IN ('RUNNING', 'COMPLETED', 'FAILED', 'STOPPED'))),
    constraint ck_execution_trigger CHECK (((trigger_type) IN ('WEBHOOK', 'MANUAL', 'SCHEDULE', 'API', 'CONNECTION'))),
    constraint fk_workflow_execution_started_from FOREIGN KEY (started_from) REFERENCES workflow_execution(id) ON DELETE SET NULL
);

INSERT INTO workflow_execution_rebuilt
    (id, workspace_id, workflow_id, workflow_name, status, trigger_type, started_at, finished_at, input, error,
     stopped_at_node_key, stopped_reason, carried, started_from, fired_trigger_id, stop_requested)
SELECT id, workspace_id, workflow_id, workflow_name, status, trigger_type, started_at, finished_at, input, error,
       stopped_at_node_key, stopped_reason, carried, started_from, fired_trigger_id, stop_requested
FROM workflow_execution;

-- The id counter goes with the rows. A copy only advances it to the highest id
-- still there, so a run deleted off the top would have its id handed out again
-- - and a run's id is in links, in sessions and in Temporal's workflow ids.
DELETE FROM sqlite_sequence WHERE name = 'workflow_execution_rebuilt';
INSERT INTO sqlite_sequence (name, seq)
SELECT 'workflow_execution_rebuilt', seq FROM sqlite_sequence WHERE name = 'workflow_execution';

DROP TABLE workflow_execution;

ALTER TABLE workflow_execution_rebuilt RENAME TO workflow_execution;

CREATE INDEX idx_workflow_execution_workflow ON workflow_execution (workflow_id, started_at DESC);
CREATE INDEX idx_workflow_execution_workspace ON workflow_execution (workspace_id, started_at DESC);

RELEASE rebuild_workflow_execution;

PRAGMA foreign_keys = ON;
