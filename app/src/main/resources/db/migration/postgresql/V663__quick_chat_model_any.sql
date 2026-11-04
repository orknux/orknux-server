-- Whether a workspace with no quick chat model of its own uses any chat model it
-- has, rather than offering no Ask AI button at all. True for every workspace:
-- the button sends nothing until somebody asks it something, and a workspace
-- that never chose had simply not found the setting. False only where an
-- administrator cleared the model on purpose - the newest quick chat entry in
-- the workspace's audit is "Quick chat model cleared" - which keeps meaning None.
-- Additive.
ALTER TABLE workspace ADD COLUMN quick_chat_model_any BOOLEAN NOT NULL DEFAULT TRUE;

UPDATE workspace w SET quick_chat_model_any = FALSE
WHERE w.quick_chat_model_id IS NULL
  AND (
    SELECT a.message FROM workspace_audit a
    WHERE a.workspace_id = w.id
      AND (a.message = 'Quick chat model cleared' OR a.message LIKE 'Quick chat model set to %')
    ORDER BY a.date DESC, a.id DESC
    LIMIT 1
  ) = 'Quick chat model cleared';
