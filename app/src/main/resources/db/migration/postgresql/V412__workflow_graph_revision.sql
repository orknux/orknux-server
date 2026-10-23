-- How many times a workflow's draft graph has been saved, so an editor saving
-- over a draft somebody else has saved since it read it is told rather than
-- writing over their work. Issue #645. Additive.
ALTER TABLE workflow ADD COLUMN graph_revision BIGINT NOT NULL DEFAULT 0;
