-- The edges a run was planned with, so it is drawn with the graph it ran rather
-- than the workflow as edited since. Null on earlier runs. Additive.

ALTER TABLE workflow_execution ADD COLUMN planned_edges TEXT;
