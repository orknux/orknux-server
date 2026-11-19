-- Postgres V689: code and Jira shapes, text that fits its shape, and the
-- copies a diagram opens with. Additive, so the rollback floor stays.
ALTER TABLE diagram_node ADD COLUMN source TEXT;
ALTER TABLE diagram_node ADD COLUMN text_fit BOOLEAN;

ALTER TABLE workspace ADD COLUMN diagram_code_function_id BIGINT;
ALTER TABLE workspace ADD COLUMN diagram_jira_function_id BIGINT;
ALTER TABLE workspace ADD COLUMN diagram_fetch_minutes INTEGER;

CREATE TABLE diagram_fetched (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    workspace_id BIGINT NOT NULL,
    function_id  BIGINT NOT NULL,
    source       VARCHAR(2000) NOT NULL,
    body         TEXT,
    fetched_at   TIMESTAMP NOT NULL,
    failed       TEXT,
    CONSTRAINT uq_diagram_fetched UNIQUE (workspace_id, function_id, source),
    CONSTRAINT diagram_fetched_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE
);
