-- Watchers: a tool an agent asked to have called on an interval until what it
-- returns matches a condition, which then wakes the agent. #606.
--
-- A row per watcher, and the row is the whole of its state - when it is next
-- due, how many times it has looked, what came back last - so a server that
-- restarts carries on from the table: the db-scheduler tick that checks them
-- keeps nothing in memory. Finished watchers stay, for the Watchers page's
-- Finished filter; status says how each one ended.
--
-- agent_id carries no foreign key on purpose. A watcher is history once it has
-- ended, and an agent deleted next month should not take the record of what it
-- watched with it; an active watcher whose agent has gone ends as FAILED at its
-- next check, which says so. The session and the workspace do cascade: a
-- watcher is about one conversation, and with the conversation gone there is
-- nobody to wake.
--
-- And session_event learns a third kind, WATCHER, for what a watcher posts to
-- its session's inbox. Additive throughout - a new table and a wider CHECK - so
-- rollback-floor stays where it is.

CREATE TABLE watcher
(
    id               BIGSERIAL PRIMARY KEY,
    workspace_id     BIGINT        NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    session_id       BIGINT        NOT NULL REFERENCES llm_session (id) ON DELETE CASCADE,
    agent_id         BIGINT,
    agent_name       VARCHAR(255)  NOT NULL,
    tool             VARCHAR(255)  NOT NULL,
    arguments        TEXT          NOT NULL,
    condition_kind   VARCHAR(16)   NOT NULL,
    condition        TEXT          NOT NULL,
    interval_seconds INTEGER       NOT NULL,
    timeout_seconds  INTEGER       NOT NULL,
    note             TEXT,
    status           VARCHAR(16)   NOT NULL,
    checks           INTEGER       NOT NULL DEFAULT 0,
    last_result      TEXT,
    matched          TEXT,
    outcome          VARCHAR(500),
    finished_by      VARCHAR(255),
    created_at       TIMESTAMPTZ   NOT NULL,
    expires_at       TIMESTAMPTZ   NOT NULL,
    next_check_at    TIMESTAMPTZ   NOT NULL,
    last_checked_at  TIMESTAMPTZ,
    finished_at      TIMESTAMPTZ,
    CONSTRAINT ck_watcher_condition_kind CHECK (condition_kind IN ('JSONPATH', 'REGEX')),
    CONSTRAINT ck_watcher_status CHECK (status IN ('ACTIVE', 'FIRED', 'TIMED_OUT', 'FINISHED', 'STOPPED', 'FAILED'))
);

CREATE INDEX ix_watcher_due ON watcher (next_check_at) WHERE status = 'ACTIVE';
CREATE INDEX ix_watcher_workspace ON watcher (workspace_id, status, created_at);

ALTER TABLE session_event DROP CONSTRAINT ck_session_event_kind;
ALTER TABLE session_event
    ADD CONSTRAINT ck_session_event_kind CHECK (kind IN ('ANSWER', 'TIMER', 'WATCHER'));

-- The three watcher tools are built-ins in BuiltInTools.GRANTED, so every agent
-- holds them already - the hidden list names what is refused, and nobody has
-- refused these. What an agent written before this does not have is the Always
-- mark a new agent is given for every built-in, which matters only under a
-- ceiling of its own: there an unmarked built-in is found rather than carried.
-- Marked as V336 marks find_connections, with the same exception for an agent
-- whose workspace lets built-ins be demoted on purpose.

INSERT INTO agent_required_tool (agent_id, position, name)
SELECT a.id,
       coalesce((SELECT max(r.position) FROM agent_required_tool r WHERE r.agent_id = a.id), -1) + 1,
       'watcher_set'
FROM agent a
JOIN workspace w ON w.id = a.workspace_id
WHERE (a.max_tools IS NULL OR w.unsafe_built_in_tools = false)
  AND NOT EXISTS (SELECT 1 FROM agent_required_tool r WHERE r.agent_id = a.id AND r.name = 'watcher_set')
  AND NOT EXISTS (SELECT 1 FROM agent_hidden_tool h WHERE h.agent_id = a.id AND h.name = 'watcher_set');

INSERT INTO agent_required_tool (agent_id, position, name)
SELECT a.id,
       coalesce((SELECT max(r.position) FROM agent_required_tool r WHERE r.agent_id = a.id), -1) + 1,
       'watcher_list'
FROM agent a
JOIN workspace w ON w.id = a.workspace_id
WHERE (a.max_tools IS NULL OR w.unsafe_built_in_tools = false)
  AND NOT EXISTS (SELECT 1 FROM agent_required_tool r WHERE r.agent_id = a.id AND r.name = 'watcher_list')
  AND NOT EXISTS (SELECT 1 FROM agent_hidden_tool h WHERE h.agent_id = a.id AND h.name = 'watcher_list');

INSERT INTO agent_required_tool (agent_id, position, name)
SELECT a.id,
       coalesce((SELECT max(r.position) FROM agent_required_tool r WHERE r.agent_id = a.id), -1) + 1,
       'watcher_finish'
FROM agent a
JOIN workspace w ON w.id = a.workspace_id
WHERE (a.max_tools IS NULL OR w.unsafe_built_in_tools = false)
  AND NOT EXISTS (SELECT 1 FROM agent_required_tool r WHERE r.agent_id = a.id AND r.name = 'watcher_finish')
  AND NOT EXISTS (SELECT 1 FROM agent_hidden_tool h WHERE h.agent_id = a.id AND h.name = 'watcher_finish');
