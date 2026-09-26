-- An image node can ask for a size, a quality and a style beside its prompt:
-- the three parameters every OpenAI-shaped image endpoint takes. Each is the
-- word the endpoint takes, held on the node; the run's own copy travels onto the
-- step like the model id does, so a node re-sized mid-run draws what it was
-- started with. Null is the model's own default, which is what every node drawn
-- before this asked for and goes on asking for. Issue #423.
ALTER TABLE workflow_node ADD COLUMN image_size varchar(16);
ALTER TABLE workflow_node ADD COLUMN image_quality varchar(16);
ALTER TABLE workflow_node ADD COLUMN image_style varchar(16);

ALTER TABLE execution_step ADD COLUMN image_size varchar(16);
ALTER TABLE execution_step ADD COLUMN image_quality varchar(16);
ALTER TABLE execution_step ADD COLUMN image_style varchar(16);
