-- The markdown plugin goes, now that Slack carries the conversion. Issue #508.
--
-- V314 already does this, and does it correctly - but it runs once, and on any
-- installation that migrated before the Slack plugin was updated it found no
-- `slack_toSlack` to point at and rightly did nothing. Flyway will not run it
-- again, so the work needs a version of its own.
--
-- Written to be safe in both directions: an installation where V314 already did
-- the job finds nothing left to move and nothing left to delete, and one that
-- still has no Slack converter is left exactly as it was rather than having a
-- live call repointed at a function that does not exist.

UPDATE workflow_action a
SET function_id = (
    SELECT f.id FROM workflow_function f
    JOIN plugin p ON p.id = f.plugin_id
    WHERE p.plugin_key = 'slack' AND f.name = 'slack_toSlack'
    LIMIT 1
)
WHERE a.function_id IN (
    SELECT f.id FROM workflow_function f
    JOIN plugin p ON p.id = f.plugin_id
    WHERE p.plugin_key = 'markdown' AND f.name = 'markdown_toSlack'
)
AND EXISTS (
    SELECT 1 FROM workflow_function f
    JOIN plugin p ON p.id = f.plugin_id
    WHERE p.plugin_key = 'slack' AND f.name = 'slack_toSlack'
);

UPDATE workflow_condition c
SET function_id = (
    SELECT f.id FROM workflow_function f
    JOIN plugin p ON p.id = f.plugin_id
    WHERE p.plugin_key = 'slack' AND f.name = 'slack_toSlack'
    LIMIT 1
)
WHERE c.function_id IN (
    SELECT f.id FROM workflow_function f
    JOIN plugin p ON p.id = f.plugin_id
    WHERE p.plugin_key = 'markdown' AND f.name = 'markdown_toSlack'
)
AND EXISTS (
    SELECT 1 FROM workflow_function f
    JOIN plugin p ON p.id = f.plugin_id
    WHERE p.plugin_key = 'slack' AND f.name = 'slack_toSlack'
);

-- And `toText` again, for an installation that has somehow still got it on the
-- plugin. Harmless where V314 already moved it.
UPDATE workflow_function
SET scope = 'EMBEDDED',
    plugin_id = NULL,
    return_object_id = NULL,
    source = 'Brought by Orknux itself; there is no code here to read.'
WHERE name = 'markdown_toText'
  AND plugin_id IN (SELECT id FROM plugin WHERE plugin_key = 'markdown');

-- Only once nothing points at it, which is the rule the Uninstall button keeps
-- and is worth as much to a migration as to somebody pressing a button.
DELETE FROM plugin
WHERE plugin_key = 'markdown'
  AND NOT EXISTS (
      SELECT 1 FROM workflow_action a
      JOIN workflow_function f ON f.id = a.function_id
      JOIN plugin p ON p.id = f.plugin_id
      WHERE p.plugin_key = 'markdown'
  )
  AND NOT EXISTS (
      SELECT 1 FROM workflow_condition c
      JOIN workflow_function f ON f.id = c.function_id
      JOIN plugin p ON p.id = f.plugin_id
      WHERE p.plugin_key = 'markdown'
  );
