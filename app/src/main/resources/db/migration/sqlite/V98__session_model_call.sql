-- See the Postgres V420: one row per model call made into a session, with the
-- timings the provider reported where it reported any.
CREATE TABLE session_model_call (
    id                   INTEGER PRIMARY KEY,
    session_id           BIGINT    NOT NULL,
    model_id             BIGINT    NOT NULL,
    at                   TIMESTAMP NOT NULL,
    input_tokens         BIGINT    NOT NULL,
    cached_input_tokens  BIGINT    NOT NULL,
    output_tokens        BIGINT    NOT NULL,
    millis               BIGINT    NOT NULL,
    prompt_tokens        BIGINT,
    prompt_millis        real,
    prompt_per_second    real,
    generated_tokens     BIGINT,
    generated_millis     real,
    generated_per_second real,
    constraint session_model_call_session_id_fkey FOREIGN KEY (session_id) REFERENCES llm_session(id) ON DELETE CASCADE
);

CREATE INDEX idx_session_model_call_session ON session_model_call (session_id, id);
CREATE INDEX idx_session_model_call_model ON session_model_call (model_id, id);
