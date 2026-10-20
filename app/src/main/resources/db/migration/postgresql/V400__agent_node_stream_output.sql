-- Whether an agent node hands on what its agent says while it is still working.
--
-- What an agent writes beside its tool calls reached the next node - a Slack
-- reply - only when the step finished, so "I'm checking this" arrived with the
-- answer, minutes later. On, each message is handed on as it is written; off,
-- the agent finishes before anything after it runs, as every node did until
-- now. On for every node, existing ones included: a reply that waits for the
-- whole turn was what people were reporting.
ALTER TABLE workflow_node ADD COLUMN stream_output BOOLEAN NOT NULL DEFAULT TRUE;

-- The run's own copy, as with the retry policy: editing the node mid-run does
-- not change a step already under way.
ALTER TABLE execution_step ADD COLUMN stream_output BOOLEAN NOT NULL DEFAULT FALSE;
