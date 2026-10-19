-- Which copy of its workflow a run ran - the draft, or a publication by id - so
-- a run of a version replaced since can say so. Null on earlier runs. Additive;
-- no foreign key, since a publication may be pruned while its runs are kept.

ALTER TABLE workflow_execution ADD COLUMN ran_draft BOOLEAN;
ALTER TABLE workflow_execution ADD COLUMN publication_id BIGINT;
