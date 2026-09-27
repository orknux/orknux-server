-- The PDF and chart bundles are the product, not plugins. Issue #501.
--
-- They were vendored into the release as plugin rows: written at boot, marked
-- built in, unremovable. That gave the product a Plugins page offering to
-- install and uninstall something the release already contained, and a row an
-- administrator could switch off by accident, for capabilities nobody thinks of
-- as integrations. Making a document is something Orknux does.
--
-- So the rows go and the bundles are read straight from the release instead.
--
-- The workflow functions they declared are kept and re-pointed rather than
-- deleted: a graph may hold one of them by id, and deleting the plugin would
-- cascade the row away and break that graph for a change that is meant to be
-- invisible. They become embedded functions with no plugin behind them.
--
-- And `built_in` goes with the last built-in plugin. A column that no row can
-- ever set again is a question the next reader has to answer twice.

-- A third scope, for what the release brings itself. The rules written in V65
-- knew two ways a function could come to exist and named an owner for each: a
-- workspace wrote it, or a plugin declared it. An embedded one has neither, and
-- that is the whole of what "embedded" means here, so the owner rule has to say
-- so rather than refuse the row.
ALTER TABLE workflow_function
    DROP CONSTRAINT ck_workflow_function_scope,
    DROP CONSTRAINT ck_workflow_function_owner;

ALTER TABLE workflow_function
    ADD CONSTRAINT ck_workflow_function_scope CHECK (scope IN ('WORKSPACE', 'PLUGIN', 'EMBEDDED')),
    ADD CONSTRAINT ck_workflow_function_owner CHECK (
        (scope = 'WORKSPACE' AND workspace_id IS NOT NULL AND plugin_id IS NULL)
        OR (scope = 'PLUGIN' AND workspace_id IS NULL AND plugin_id IS NOT NULL)
        OR (scope = 'EMBEDDED' AND workspace_id IS NULL AND plugin_id IS NULL)
    );

-- And one name each, the way a plugin's functions have one name each: the
-- registrar looks a row up by scope and name, so two would make which one it
-- finds a matter of luck.
CREATE UNIQUE INDEX uk_workflow_function_embedded_name
    ON workflow_function (name)
    WHERE scope = 'EMBEDDED';

UPDATE workflow_function
SET scope = 'EMBEDDED',
    plugin_id = NULL
WHERE plugin_id IN (SELECT id FROM plugin WHERE plugin_key IN ('pdf', 'charts') AND built_in = true);

DELETE FROM plugin
WHERE plugin_key IN ('pdf', 'charts')
  AND built_in = true;

ALTER TABLE plugin
    DROP COLUMN built_in;
