-- What an agent step's agent wrote in the passes before the one that ends it,
-- kept across a sleep: the step hands on everything the agent wrote, not only
-- its last answer. Nullable, so rollback-floor stays where it is.
ALTER TABLE execution_step ADD COLUMN agent_said text;
