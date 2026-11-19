-- Code shapes, read from a GitHub file, and Jira shapes, read from an issue,
-- each through the function the workspace chose for it; text that fits its
-- shape; and the copies a diagram opens with, so GitHub and Jira are asked
-- only when a copy is older than the workspace keeps one.
--
-- Additive - nullable columns and a new table - so the rollback floor stays.
ALTER TABLE diagram_node ADD COLUMN source TEXT;
ALTER TABLE diagram_node ADD COLUMN text_fit BOOLEAN;

ALTER TABLE workspace ADD COLUMN diagram_code_function_id BIGINT;
ALTER TABLE workspace ADD COLUMN diagram_jira_function_id BIGINT;
ALTER TABLE workspace ADD COLUMN diagram_fetch_minutes INTEGER;

CREATE TABLE diagram_fetched (
    id           BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    function_id  BIGINT NOT NULL,
    source       VARCHAR(2000) NOT NULL,
    body         TEXT,
    fetched_at   TIMESTAMPTZ NOT NULL,
    failed       TEXT,
    CONSTRAINT uq_diagram_fetched UNIQUE (workspace_id, function_id, source)
);
