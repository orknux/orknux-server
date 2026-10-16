-- What an agent step's agent wrote in the passes before the one that ends it,
-- kept across a sleep. Postgres V364.
ALTER TABLE execution_step ADD COLUMN agent_said TEXT;
