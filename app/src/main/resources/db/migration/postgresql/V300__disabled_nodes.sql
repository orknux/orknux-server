-- A node that is kept on the graph and skipped by the run.
--
-- Trying one part of a workflow meant deleting the parts around it: the Slack
-- post at the end goes out forty times while the agent before it is tuned, and
-- the only way to stop it was to remove the node and redraw its lines when the
-- tuning was done. A disabled node stays where it is, with every edge it had,
-- and the run walks straight through it - the step is recorded as skipped and
-- what reached it is handed on unchanged.
--
-- On the node rather than on the workflow because that is the grain the
-- question is asked at: the workflow's own switch already says whether anything
-- starts it, and this says which of its pieces do their work once it has.
--
-- The run's copy of the step carries it too, for the reason every other column
-- on execution_step is a copy: a node switched back on while a run is between
-- steps must not change what that run does, and the engine picks a step up by
-- its row rather than by the graph it came from.
--
-- On by default, so every existing node goes on doing what it did. Issue #439.
ALTER TABLE workflow_node
    ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE execution_step
    ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;
