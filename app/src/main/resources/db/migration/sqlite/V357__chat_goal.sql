-- Goals: a chat's agent keeps working until a stated condition is met, with a
-- reviewer agent judging each turn. The same as Postgres V677. Additive.
CREATE TABLE chat_goal
(
    id                    INTEGER PRIMARY KEY AUTOINCREMENT,
    workspace_id          BIGINT       NOT NULL,
    llm_session_id        BIGINT       NOT NULL,
    chat_id               BIGINT,
    goal                  text         NOT NULL,
    condition             text         NOT NULL,
    max_iterations        integer      NOT NULL,
    iteration             integer      NOT NULL DEFAULT 0,
    status                varchar(16)  NOT NULL,
    last_feedback         text,
    reviewer_model_id     BIGINT       NOT NULL,
    reviewer_instructions text,
    reviewer_agent_id     BIGINT,
    started_by            varchar(200) NOT NULL,
    created_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_chat_goal_status CHECK (status IN ('RUNNING', 'MET', 'STOPPED', 'EXHAUSTED'))
);
CREATE INDEX idx_chat_goal_session ON chat_goal (llm_session_id);
CREATE INDEX idx_chat_goal_chat ON chat_goal (chat_id);
