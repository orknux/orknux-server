-- Stripping markdown to text is the product's; writing Slack's dialect is not.
-- Issue #508.
--
-- The markdown plugin declared two functions and they go to different places.
-- `toText` is markdown with its punctuation taken off, for an email subject or
-- a log line - not an integration with anything, so it comes into core and is
-- re-pointed by the same rule as V312 and V313: the row keeps its id, so
-- anything naming it goes on working.
--
-- `toSlack` writes Slack's *mrkdwn*, which is Slack's own dialect and belongs
-- with Slack. It has moved into that plugin, so the calls move with it.

UPDATE workflow_function
SET scope = 'EMBEDDED',
    plugin_id = NULL,
    return_object_id = NULL,
    source = 'Brought by Orknux itself; there is no code here to read.'
WHERE name = 'markdown_toText'
  AND plugin_id IN (SELECT id FROM plugin WHERE plugin_key = 'markdown');

-- The calls follow the function to its new home.
--
-- Matched by the shape of the name rather than by one exact spelling, because
-- the Slack plugin is released on its own cadence and this has to do the right
-- thing whether it calls the function `toSlack` or `toMrkdwn`. Nothing happens
-- at all where the newer plugin is not installed yet: the action goes on
-- pointing at the markdown plugin, which goes on working, and this runs again
-- to no effect on the next start. A migration that repointed a live call at a
-- function which does not exist would break a workflow to tidy a table.
UPDATE workflow_action a
SET function_id = (
    SELECT f.id FROM workflow_function f
    JOIN plugin p ON p.id = f.plugin_id
    WHERE p.plugin_key = 'slack' AND (f.name = 'slack_toSlack' OR f.name = 'slack_toMrkdwn')
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
    WHERE p.plugin_key = 'slack' AND (f.name = 'slack_toSlack' OR f.name = 'slack_toMrkdwn')
);

UPDATE workflow_condition c
SET function_id = (
    SELECT f.id FROM workflow_function f
    JOIN plugin p ON p.id = f.plugin_id
    WHERE p.plugin_key = 'slack' AND (f.name = 'slack_toSlack' OR f.name = 'slack_toMrkdwn')
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
    WHERE p.plugin_key = 'slack' AND (f.name = 'slack_toSlack' OR f.name = 'slack_toMrkdwn')
);

-- And the plugin goes once nothing is left pointing at it. Only then: it is
-- refused an unload while anything calls its functions, and that rule is worth
-- as much to a migration as it is to somebody pressing the button.
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
