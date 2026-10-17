-- See the Postgres V369: one row per model call made while usage tracking was
-- on, naming who prompted the turn it ran in.
CREATE TABLE person_usage (
    id                  INTEGER PRIMARY KEY,
    at                  TIMESTAMP     NOT NULL,
    workspace_id        BIGINT        NOT NULL,
    session_id          BIGINT        NOT NULL,
    model_id            BIGINT        NOT NULL,
    source              VARCHAR(16)   NOT NULL,
    connection_id       BIGINT,
    person_key          VARCHAR(255)  NOT NULL,
    person_name         VARCHAR(255),
    input_tokens        BIGINT        NOT NULL,
    cached_input_tokens BIGINT        NOT NULL,
    output_tokens       BIGINT        NOT NULL,
    cost                NUMERIC(20, 8) NOT NULL,
    unpriced            BOOLEAN       NOT NULL
);

CREATE INDEX idx_person_usage_at ON person_usage (at);
CREATE INDEX idx_person_usage_person ON person_usage (source, connection_id, person_key);
CREATE INDEX idx_person_usage_session ON person_usage (session_id);
