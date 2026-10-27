-- See the Postgres V636: a flag an agent raised on the session it was running
-- in, cleared rather than deleted, and flag_session and unflag_session marked
-- Always for the agents that predate them.
CREATE TABLE session_flag (
    id            INTEGER PRIMARY KEY,
    flagged_at    TIMESTAMP     NOT NULL,
    workspace_id  BIGINT        NOT NULL,
    session_id    BIGINT        NOT NULL,
    agent_name    VARCHAR(255)  NOT NULL,
    type          VARCHAR(32)   NOT NULL,
    severity      INTEGER       NOT NULL,
    note          TEXT          NOT NULL,
    cleared_at    TIMESTAMP,
    cleared_by    VARCHAR(255),
    CONSTRAINT ck_session_flag_type CHECK (type IN ('SECURITY', 'TECHNICAL_ISSUE')),
    CONSTRAINT ck_session_flag_severity CHECK (severity BETWEEN 0 AND 5)
);

CREATE INDEX idx_session_flag_flagged_at ON session_flag (flagged_at);
CREATE INDEX idx_session_flag_session ON session_flag (session_id);

WITH basic_name (ord, name) AS (
  VALUES
    (1, 'flag_session'),
    (2, 'unflag_session')
),
missing AS (
  SELECT a.id AS agent_id, t.ord, t.name
  FROM agent a
  CROSS JOIN basic_name t
  WHERE NOT EXISTS (SELECT 1 FROM agent_hidden_tool h WHERE h.agent_id = a.id AND h.name = t.name)
    AND NOT EXISTS (SELECT 1 FROM agent_required_tool r WHERE r.agent_id = a.id AND r.name = t.name)
)
INSERT INTO agent_required_tool (agent_id, position, name)
SELECT m.agent_id,
       coalesce((SELECT max(r.position) FROM agent_required_tool r WHERE r.agent_id = m.agent_id), -1)
         + row_number() OVER (PARTITION BY m.agent_id ORDER BY m.ord),
       m.name
FROM missing m;
