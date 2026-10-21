-- See the Postgres V403: a review a person gave a conversation, a score from
-- 1 to 5 and their words if any, and rate_session marked Always.
CREATE TABLE session_rating (
    id            INTEGER PRIMARY KEY,
    at            TIMESTAMP     NOT NULL,
    workspace_id  BIGINT        NOT NULL,
    session_id    BIGINT        NOT NULL,
    agent_name    VARCHAR(255)  NOT NULL,
    score         INTEGER       NOT NULL,
    review        TEXT,
    source        VARCHAR(16)   NOT NULL,
    connection_id BIGINT,
    person_key    VARCHAR(255)  NOT NULL,
    person_name   VARCHAR(255),
    CONSTRAINT ck_session_rating_score CHECK (score BETWEEN 1 AND 5)
);

CREATE INDEX idx_session_rating_at ON session_rating (at);
CREATE INDEX idx_session_rating_session ON session_rating (session_id);

INSERT INTO agent_required_tool (agent_id, position, name)
SELECT a.id,
       coalesce((SELECT max(r.position) FROM agent_required_tool r WHERE r.agent_id = a.id), -1) + 1,
       'rate_session'
FROM agent a
JOIN workspace w ON w.id = a.workspace_id
WHERE (a.max_tools IS NULL OR w.unsafe_built_in_tools = 0)
  AND NOT EXISTS (SELECT 1 FROM agent_required_tool r WHERE r.agent_id = a.id AND r.name = 'rate_session')
  AND NOT EXISTS (SELECT 1 FROM agent_hidden_tool h WHERE h.agent_id = a.id AND h.name = 'rate_session');
