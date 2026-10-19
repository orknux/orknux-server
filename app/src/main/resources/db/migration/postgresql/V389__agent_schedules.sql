-- Schedules: a repeating reminder an agent sets for itself, the periodic
-- counterpart of timer_set. Every so many seconds, or on a five-field cron,
-- the schedule leaves its note in its session's inbox, which wakes the session
-- the way a timer coming due does.
--
-- A row per schedule, and the row is the whole of its state - when it next
-- runs, how many times it has - because the db-scheduler tick that runs them
-- keeps nothing in memory, so a server that restarts carries on from the
-- table. Ended and stopped schedules stay, for the Schedules page's filter.
--
-- Exactly one of every_seconds and cron, which ck_agent_schedule_shape holds.
-- until_at and max_runs are each optional: an end time and a number of runs.
--
-- agent_id carries no foreign key, as watcher's does not: a schedule is
-- history once it has ended, and deleting an agent should not delete the
-- record of what it set. The session and the workspace do cascade - a schedule
-- is about one conversation, and with it gone there is nobody to remind.
-- CASCADE rather than SET NULL, because session_id is NOT NULL (#169).
--
-- And session_event learns a fifth kind, SCHEDULE, for what a schedule posts.
-- Additive throughout - a new table and a wider CHECK - so rollback-floor stays.

CREATE TABLE agent_schedule
(
    id            BIGSERIAL PRIMARY KEY,
    workspace_id  BIGINT        NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    session_id    BIGINT        NOT NULL REFERENCES llm_session (id) ON DELETE CASCADE,
    agent_id      BIGINT,
    agent_name    VARCHAR(255)  NOT NULL,
    note          TEXT          NOT NULL,
    every_seconds INTEGER,
    cron          VARCHAR(255),
    timezone      VARCHAR(64),
    status        VARCHAR(16)   NOT NULL,
    runs          INTEGER       NOT NULL DEFAULT 0,
    max_runs      INTEGER,
    until_at      TIMESTAMPTZ,
    created_at    TIMESTAMPTZ   NOT NULL,
    next_run_at   TIMESTAMPTZ,
    last_run_at   TIMESTAMPTZ,
    finished_at   TIMESTAMPTZ,
    outcome       VARCHAR(500),
    CONSTRAINT ck_agent_schedule_status CHECK (status IN ('ACTIVE', 'STOPPED', 'ENDED')),
    CONSTRAINT ck_agent_schedule_shape CHECK (
        (every_seconds IS NOT NULL AND cron IS NULL) OR (every_seconds IS NULL AND cron IS NOT NULL)
    )
);

CREATE INDEX ix_agent_schedule_due ON agent_schedule (next_run_at) WHERE status = 'ACTIVE';
CREATE INDEX ix_agent_schedule_workspace ON agent_schedule (workspace_id, status, created_at);
CREATE INDEX ix_agent_schedule_session ON agent_schedule (session_id);

ALTER TABLE session_event DROP CONSTRAINT ck_session_event_kind;
ALTER TABLE session_event
    ADD CONSTRAINT ck_session_event_kind CHECK (kind IN ('ANSWER', 'TIMER', 'WATCHER', 'PERSON', 'SCHEDULE'));

-- The three schedule tools are built-ins in BuiltInTools.GRANTED, so every
-- agent holds them already (V309: a built-in is offered unless hidden). What an
-- agent written before this lacks is the Always mark a new agent is given for
-- every built-in, which matters under a ceiling of its own. Marked as V337
-- marks the watcher tools, on the same terms.

INSERT INTO agent_required_tool (agent_id, position, name)
SELECT a.id,
       coalesce((SELECT max(r.position) FROM agent_required_tool r WHERE r.agent_id = a.id), -1) + 1,
       'schedule_set'
FROM agent a
JOIN workspace w ON w.id = a.workspace_id
WHERE (a.max_tools IS NULL OR w.unsafe_built_in_tools = false)
  AND NOT EXISTS (SELECT 1 FROM agent_required_tool r WHERE r.agent_id = a.id AND r.name = 'schedule_set')
  AND NOT EXISTS (SELECT 1 FROM agent_hidden_tool h WHERE h.agent_id = a.id AND h.name = 'schedule_set');

INSERT INTO agent_required_tool (agent_id, position, name)
SELECT a.id,
       coalesce((SELECT max(r.position) FROM agent_required_tool r WHERE r.agent_id = a.id), -1) + 1,
       'schedule_list'
FROM agent a
JOIN workspace w ON w.id = a.workspace_id
WHERE (a.max_tools IS NULL OR w.unsafe_built_in_tools = false)
  AND NOT EXISTS (SELECT 1 FROM agent_required_tool r WHERE r.agent_id = a.id AND r.name = 'schedule_list')
  AND NOT EXISTS (SELECT 1 FROM agent_hidden_tool h WHERE h.agent_id = a.id AND h.name = 'schedule_list');

INSERT INTO agent_required_tool (agent_id, position, name)
SELECT a.id,
       coalesce((SELECT max(r.position) FROM agent_required_tool r WHERE r.agent_id = a.id), -1) + 1,
       'schedule_cancel'
FROM agent a
JOIN workspace w ON w.id = a.workspace_id
WHERE (a.max_tools IS NULL OR w.unsafe_built_in_tools = false)
  AND NOT EXISTS (SELECT 1 FROM agent_required_tool r WHERE r.agent_id = a.id AND r.name = 'schedule_cancel')
  AND NOT EXISTS (SELECT 1 FROM agent_hidden_tool h WHERE h.agent_id = a.id AND h.name = 'schedule_cancel');
