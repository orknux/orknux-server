-- Goals: a chat's agent keeps working until a stated condition is met, with a
-- reviewer agent judging each turn. One row per goal, the newest being the
-- chat's current one. Additive: a new table an older jar does not read.
CREATE TABLE chat_goal
(
    id                    bigserial PRIMARY KEY,
    workspace_id          bigint       NOT NULL,
    -- The conversation it runs in, and the chat that conversation is, by id
    -- without a foreign key: a goal outliving its chat is history, not a cascade.
    llm_session_id        bigint       NOT NULL,
    chat_id               bigint,
    goal                  text         NOT NULL,
    condition             text         NOT NULL,
    max_iterations        integer      NOT NULL,
    iteration             integer      NOT NULL DEFAULT 0,
    status                varchar(16)  NOT NULL,
    last_feedback         text,
    reviewer_model_id     bigint       NOT NULL,
    reviewer_instructions text,
    reviewer_agent_id     bigint,
    started_by            varchar(200) NOT NULL,
    created_at            timestamptz  NOT NULL DEFAULT now(),
    updated_at            timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_chat_goal_status CHECK (status IN ('RUNNING', 'MET', 'STOPPED', 'EXHAUSTED'))
);
CREATE INDEX idx_chat_goal_session ON chat_goal (llm_session_id);
CREATE INDEX idx_chat_goal_chat ON chat_goal (chat_id);
