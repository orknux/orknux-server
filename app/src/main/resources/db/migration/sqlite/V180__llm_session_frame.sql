-- The system turn and tool array a session's requests carry in front of their
-- messages, as first sent. The postgres copy, V500, says why.

CREATE TABLE llm_session_frame
(
    session_id  integer not null primary key,
    agent_id    integer not null,
    model_id    integer not null,
    agent_stamp integer not null,
    system_turn text,
    tools       text not null,
    hunting     boolean not null default false,
    seen_system text,
    seen_tools  text not null default '',
    updated_at  timestamp not null default CURRENT_TIMESTAMP,
    constraint llm_session_frame_session_id_fkey FOREIGN KEY (session_id) REFERENCES llm_session(id) ON DELETE CASCADE
);
