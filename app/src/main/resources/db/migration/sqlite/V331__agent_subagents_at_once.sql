-- How many of the agents an agent asked may be working at once. Postgres V651.
ALTER TABLE agent ADD COLUMN max_subagents_at_once INTEGER;
