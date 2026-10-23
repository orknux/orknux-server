-- See the Postgres V412: how many times a workflow's draft graph has been saved.
ALTER TABLE workflow ADD COLUMN graph_revision INTEGER NOT NULL DEFAULT 0;
