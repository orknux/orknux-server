-- The messages a session's model was actually sent, so the next turn can send
-- them again exactly. The postgres copy, V349, carries the reasoning.

CREATE TABLE llm_session_prompt
(
    id               integer not null primary key autoincrement,
    session_id       integer not null,
    turn_no          integer not null,
    opening          boolean not null default false,
    message          text not null,
    through_event_id integer,
    chars            integer not null default 0,
    created_at       timestamp not null default CURRENT_TIMESTAMP,
    constraint llm_session_prompt_session_id_fkey FOREIGN KEY (session_id) REFERENCES llm_session(id) ON DELETE CASCADE
);

CREATE INDEX llm_session_prompt_session_idx ON llm_session_prompt (session_id, id);
