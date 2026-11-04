-- How hard an agent's model thinks, on the agent: what a chat and a workflow
-- node send unless they ask for their own. It was the model's, set on the
-- model page, and every agent sharing a model shared it - a quick triage
-- agent and a careful reviewer on one deployment could not differ.
--
-- Carried over, so nothing answers differently after the upgrade: each agent
-- takes the effort its model had, and the model's is cleared, since the page
-- no longer shows it and a value nobody can see is one nobody can change.
-- Additive: a nullable column; an older jar reads the model's column, now
-- null, and sends the model's own default, as it would for any model unset.
ALTER TABLE agent ADD COLUMN reasoning_effort VARCHAR(16);

UPDATE agent
SET reasoning_effort = (SELECT m.reasoning_effort FROM llm_model m WHERE m.id = agent.model_id)
WHERE reasoning_effort IS NULL
  AND model_id IS NOT NULL;

UPDATE llm_model SET reasoning_effort = NULL WHERE reasoning_effort IS NOT NULL;
