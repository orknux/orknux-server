-- Watchers, #606. The postgres copy, V337, carries the reasoning.
--
-- session_event is built again for its wider CHECK, since SQLite cannot change
-- one in place. Nothing points at session_event, so it is copied, dropped and
-- renamed, and its two indexes made again under their own names.

CREATE TABLE watcher
(
    id               integer not null primary key autoincrement,
    workspace_id     integer not null,
    session_id       integer not null,
    agent_id         integer,
    agent_name       varchar(255) not null,
    tool             varchar(255) not null,
    arguments        text not null,
    condition_kind   varchar(16) not null,
    condition        text not null,
    interval_seconds integer not null,
    timeout_seconds  integer not null,
    note             text,
    status           varchar(16) not null,
    checks           integer not null default 0,
    last_result      text,
    matched          text,
    outcome          varchar(500),
    finished_by      varchar(255),
    created_at       timestamp not null,
    expires_at       timestamp not null,
    next_check_at    timestamp not null,
    last_checked_at  timestamp,
    finished_at      timestamp,
    constraint watcher_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE,
    constraint watcher_session_id_fkey FOREIGN KEY (session_id) REFERENCES llm_session(id) ON DELETE CASCADE,
    constraint ck_watcher_condition_kind CHECK (condition_kind IN ('JSONPATH', 'REGEX')),
    constraint ck_watcher_status CHECK (status IN ('ACTIVE', 'FIRED', 'TIMED_OUT', 'FINISHED', 'STOPPED', 'FAILED'))
);

CREATE INDEX ix_watcher_due ON watcher (next_check_at) WHERE status = 'ACTIVE';
CREATE INDEX ix_watcher_workspace ON watcher (workspace_id, status, created_at);

CREATE TABLE session_event_new
(
    id                           integer not null primary key autoincrement,
    session_id                   integer not null,
    kind                         varchar(16) not null,
    body                         text not null,
    due_at                       timestamp not null default CURRENT_TIMESTAMP,
    delivered_at                 timestamp,
    created_at                   timestamp not null default CURRENT_TIMESTAMP,
    constraint session_event_session_id_fkey FOREIGN KEY (session_id) REFERENCES llm_session(id) ON DELETE CASCADE,
    constraint ck_session_event_kind CHECK (kind IN ('ANSWER', 'TIMER', 'WATCHER'))
);

INSERT INTO session_event_new (id, session_id, kind, body, due_at, delivered_at, created_at)
SELECT id, session_id, kind, body, due_at, delivered_at, created_at FROM session_event;

DROP TABLE session_event;

ALTER TABLE session_event_new RENAME TO session_event;

CREATE INDEX ix_session_event_waiting ON session_event (session_id, due_at) WHERE delivered_at IS NULL;
CREATE INDEX ix_session_event_due ON session_event (due_at) WHERE delivered_at IS NULL;

-- The Always marks for the watcher tools, as V336 / sqlite V14 did for find_connections.

INSERT INTO agent_required_tool (agent_id, position, name)
SELECT a.id,
       coalesce((SELECT max(r.position) FROM agent_required_tool r WHERE r.agent_id = a.id), -1) + 1,
       'watcher_set'
FROM agent a
JOIN workspace w ON w.id = a.workspace_id
WHERE (a.max_tools IS NULL OR w.unsafe_built_in_tools = 0)
  AND NOT EXISTS (SELECT 1 FROM agent_required_tool r WHERE r.agent_id = a.id AND r.name = 'watcher_set')
  AND NOT EXISTS (SELECT 1 FROM agent_hidden_tool h WHERE h.agent_id = a.id AND h.name = 'watcher_set');

INSERT INTO agent_required_tool (agent_id, position, name)
SELECT a.id,
       coalesce((SELECT max(r.position) FROM agent_required_tool r WHERE r.agent_id = a.id), -1) + 1,
       'watcher_list'
FROM agent a
JOIN workspace w ON w.id = a.workspace_id
WHERE (a.max_tools IS NULL OR w.unsafe_built_in_tools = 0)
  AND NOT EXISTS (SELECT 1 FROM agent_required_tool r WHERE r.agent_id = a.id AND r.name = 'watcher_list')
  AND NOT EXISTS (SELECT 1 FROM agent_hidden_tool h WHERE h.agent_id = a.id AND h.name = 'watcher_list');

INSERT INTO agent_required_tool (agent_id, position, name)
SELECT a.id,
       coalesce((SELECT max(r.position) FROM agent_required_tool r WHERE r.agent_id = a.id), -1) + 1,
       'watcher_finish'
FROM agent a
JOIN workspace w ON w.id = a.workspace_id
WHERE (a.max_tools IS NULL OR w.unsafe_built_in_tools = 0)
  AND NOT EXISTS (SELECT 1 FROM agent_required_tool r WHERE r.agent_id = a.id AND r.name = 'watcher_finish')
  AND NOT EXISTS (SELECT 1 FROM agent_hidden_tool h WHERE h.agent_id = a.id AND h.name = 'watcher_finish');
