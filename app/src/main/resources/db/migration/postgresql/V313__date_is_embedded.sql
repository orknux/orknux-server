-- The working calendar is the product's, not a plugin's. Issue #512.
--
-- Knowing what day it is, and whether that day is a working one, is not an
-- integration with somebody else's system. The built-in clock already answered
-- half of it, and a model reaching for a plugin to ask whether a deadline has
-- passed is a model reaching past the product.
--
-- The rows are re-pointed rather than deleted and rewritten, exactly as V312
-- did for the pdf and the charts, and for the same reason: a workflow action
-- names a function by its id. Twenty-four actions in this installation alone
-- point at date_businessDaysBetween and date_between, and deleting the plugin
-- would cascade those rows away and break every one of them. Re-pointing means
-- an action keeps working and nobody has to edit a graph.
--
-- That is also what makes the plugin removable. It was refused - rightly -
-- while anything still called its functions, and this is what stops anything
-- calling them: the functions are still there, under the same names and the
-- same ids, answered by Orknux itself.

UPDATE workflow_function
SET scope = 'EMBEDDED',
    plugin_id = NULL,
    return_object_id = NULL,
    source = 'Brought by Orknux itself; there is no code here to read.'
WHERE plugin_id IN (SELECT id FROM plugin WHERE plugin_key = 'date');

DELETE FROM plugin WHERE plugin_key = 'date';
