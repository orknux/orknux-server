-- Schedules. The postgres copy, V389, carries the reasoning.
--
-- session_event is built again for its wider CHECK, since SQLite cannot change
-- one in place - the way V15 and V34 did. Nothing points at session_event, so
-- it is copied, dropped and renamed, and its two indexes made again.

CREATE TABLE agent_schedule
(
    id            integer not null primary key autoincrement,
    workspace_id  integer not null,
    session_id    integer not null,
    agent_id      integer,
    agent_name    varchar(255) not null,
    note          text not null,
    every_seconds integer,
    cron          varchar(255),
    timezone      varchar(64),
    status        varchar(16) not null,
    runs          integer not null default 0,
    max_runs      integer,
    until_at      timestamp,
    created_at    timestamp not null,
    next_run_at   timestamp,
    last_run_at   timestamp,
    finished_at   timestamp,
    outcome       varchar(500),
    constraint agent_schedule_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE,
    constraint agent_schedule_session_id_fkey FOREIGN KEY (session_id) REFERENCES llm_session(id) ON DELETE CASCADE,
    constraint ck_agent_schedule_status CHECK (status IN ('ACTIVE', 'STOPPED', 'ENDED')),
    constraint ck_agent_schedule_shape CHECK (
        (every_seconds IS NOT NULL AND cron IS NULL) OR (every_seconds IS NULL AND cron IS NOT NULL)
    )
);

CREATE INDEX ix_agent_schedule_due ON agent_schedule (next_run_at) WHERE status = 'ACTIVE';
CREATE INDEX ix_agent_schedule_workspace ON agent_schedule (workspace_id, status, created_at);
CREATE INDEX ix_agent_schedule_session ON agent_schedule (session_id);

CREATE TABLE session_event_new
(
    id                           integer not null primary key autoincrement,
    session_id                   integer not null,
    kind                         varchar(16) not null,
    body                         text not null,
    due_at                       timestamp not null default CURRENT_TIMESTAMP,
    delivered_at                 timestamp,
    created_at                   timestamp not null default CURRENT_TIMESTAMP,
    said_by                      varchar(255),
    constraint session_event_session_id_fkey FOREIGN KEY (session_id) REFERENCES llm_session(id) ON DELETE CASCADE,
    constraint ck_session_event_kind CHECK (kind IN ('ANSWER', 'TIMER', 'WATCHER', 'PERSON', 'SCHEDULE'))
);

INSERT INTO session_event_new (id, session_id, kind, body, due_at, delivered_at, created_at, said_by)
SELECT id, session_id, kind, body, due_at, delivered_at, created_at, said_by FROM session_event;

DROP TABLE session_event;

ALTER TABLE session_event_new RENAME TO session_event;

CREATE INDEX ix_session_event_waiting ON session_event (session_id, due_at) WHERE delivered_at IS NULL;
CREATE INDEX ix_session_event_due ON session_event (due_at) WHERE delivered_at IS NULL;

-- The Always marks for the schedule tools, as V15 did for the watcher tools.

INSERT INTO agent_required_tool (agent_id, position, name)
SELECT a.id,
       coalesce((SELECT max(r.position) FROM agent_required_tool r WHERE r.agent_id = a.id), -1) + 1,
       'schedule_set'
FROM agent a
JOIN workspace w ON w.id = a.workspace_id
WHERE (a.max_tools IS NULL OR w.unsafe_built_in_tools = 0)
  AND NOT EXISTS (SELECT 1 FROM agent_required_tool r WHERE r.agent_id = a.id AND r.name = 'schedule_set')
  AND NOT EXISTS (SELECT 1 FROM agent_hidden_tool h WHERE h.agent_id = a.id AND h.name = 'schedule_set');

INSERT INTO agent_required_tool (agent_id, position, name)
SELECT a.id,
       coalesce((SELECT max(r.position) FROM agent_required_tool r WHERE r.agent_id = a.id), -1) + 1,
       'schedule_list'
FROM agent a
JOIN workspace w ON w.id = a.workspace_id
WHERE (a.max_tools IS NULL OR w.unsafe_built_in_tools = 0)
  AND NOT EXISTS (SELECT 1 FROM agent_required_tool r WHERE r.agent_id = a.id AND r.name = 'schedule_list')
  AND NOT EXISTS (SELECT 1 FROM agent_hidden_tool h WHERE h.agent_id = a.id AND h.name = 'schedule_list');

INSERT INTO agent_required_tool (agent_id, position, name)
SELECT a.id,
       coalesce((SELECT max(r.position) FROM agent_required_tool r WHERE r.agent_id = a.id), -1) + 1,
       'schedule_cancel'
FROM agent a
JOIN workspace w ON w.id = a.workspace_id
WHERE (a.max_tools IS NULL OR w.unsafe_built_in_tools = 0)
  AND NOT EXISTS (SELECT 1 FROM agent_required_tool r WHERE r.agent_id = a.id AND r.name = 'schedule_cancel')
  AND NOT EXISTS (SELECT 1 FROM agent_hidden_tool h WHERE h.agent_id = a.id AND h.name = 'schedule_cancel');
