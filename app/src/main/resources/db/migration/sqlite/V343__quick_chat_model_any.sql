-- Whether a workspace with no quick chat model of its own uses any chat model it
-- has; see the Postgres V663.
ALTER TABLE workspace ADD COLUMN quick_chat_model_any BOOLEAN NOT NULL DEFAULT TRUE;

UPDATE workspace SET quick_chat_model_any = FALSE
WHERE quick_chat_model_id IS NULL
  AND (
    SELECT a.message FROM workspace_audit a
    WHERE a.workspace_id = workspace.id
      AND (a.message = 'Quick chat model cleared' OR a.message LIKE 'Quick chat model set to %')
    ORDER BY a.date DESC, a.id DESC
    LIMIT 1
  ) = 'Quick chat model cleared';
