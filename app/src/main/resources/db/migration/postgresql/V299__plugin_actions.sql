-- A plugin can declare workflow actions, and an Action node can be one. Issue #438.
--
-- A plugin's functions were the only way a plugin reached a workflow, and a
-- function is called positionally with the arguments its row declares - so a
-- trigger's list of commands had no way to arrive as the list it is, and every
-- plugin block on a canvas read as "Function". A plugin now declares actions -
-- a name, a label for the picker, parameters and outputs by name, and a run
-- handed one object - and an Action of subtype PLUGIN_ACTION names one.

-- What the plugin declared, as JSON beside its other declarations, so the
-- editor lists them and the runner reads a node's inputs off them without
-- entering the sandbox.
ALTER TABLE plugin ADD COLUMN declared_actions text NOT NULL DEFAULT '[]';

-- Which plugin's action, and what the plugin calls it. Two names rather than an
-- id, because a plugin's declarations have no rows: they are replaced whole on
-- every load, and a key and a name survive that the way a function id survives
-- an edit.
ALTER TABLE workflow_action
    ADD COLUMN plugin_key varchar(64),
    ADD COLUMN plugin_action varchar(64);

ALTER TABLE workflow_action
    DROP CONSTRAINT ck_workflow_action_subtype;
ALTER TABLE workflow_action
    ADD CONSTRAINT ck_workflow_action_subtype
        CHECK (subtype IN ('OUTGOING_CONNECTION', 'SEND_EMAIL', 'HTTP_REQUEST', 'FUNCTION', 'PLUGIN_ACTION',
                           'INLINE_CONDITION', 'CONDITION', 'TIME', 'SPEAK'));

-- A shared PLUGIN_ACTION names both halves. SPEAK joins the list too: V277 added
-- the subtype without a shape clause, so a shared spoken action had no clause
-- that could hold and could not be stored at all.
ALTER TABLE workflow_action DROP CONSTRAINT IF EXISTS ck_workflow_action_shape;
ALTER TABLE workflow_action ADD CONSTRAINT ck_workflow_action_shape CHECK (
    workflow_id IS NOT NULL OR (
        (type = 'EXECUTE' AND subtype = 'OUTGOING_CONNECTION' AND connection_id IS NOT NULL) OR
        (type = 'EXECUTE' AND subtype = 'SEND_EMAIL' AND connection_id IS NOT NULL) OR
        (type = 'EXECUTE' AND subtype = 'HTTP_REQUEST' AND url IS NOT NULL) OR
        (type = 'EXECUTE' AND subtype = 'FUNCTION' AND function_id IS NOT NULL) OR
        (type = 'EXECUTE' AND subtype = 'PLUGIN_ACTION' AND plugin_key IS NOT NULL AND plugin_action IS NOT NULL) OR
        (type = 'EXECUTE' AND subtype = 'SPEAK' AND speech_text IS NOT NULL) OR
        (type = 'WAIT' AND subtype = 'INLINE_CONDITION' AND condition_expression IS NOT NULL) OR
        (type = 'WAIT' AND subtype = 'CONDITION' AND condition_id IS NOT NULL) OR
        (type = 'WAIT' AND subtype = 'TIME' AND duration_seconds IS NOT NULL)
    )
);
