-- How long one attempt of a node's work may take, in seconds; null is as long
-- as it takes. Additive: an older jar never reads the column and lets every
-- node run as long as it always did.
ALTER TABLE workflow_node ADD COLUMN timeout_seconds INTEGER;

-- The run's own copy, as with the retry policy and the repeat count.
ALTER TABLE execution_step ADD COLUMN timeout_seconds INTEGER;
