-- What outline the canvas draws a node with. Picture only, like orientation;
-- null is the plain card every node was drawn as before.
ALTER TABLE workflow_node ADD COLUMN shape VARCHAR(16);
