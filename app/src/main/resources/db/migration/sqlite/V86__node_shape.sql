-- See the Postgres V408: what outline the canvas draws a node with.
ALTER TABLE workflow_node ADD COLUMN shape VARCHAR(16);
