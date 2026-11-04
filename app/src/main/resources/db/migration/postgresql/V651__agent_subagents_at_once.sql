-- How many of the agents an agent asked may be working at once, in one of its
-- conversations. Null follows Admin -> Settings, which is where every agent
-- starts. Nullable, so rollback-floor stays where it is.
ALTER TABLE agent ADD COLUMN max_subagents_at_once INTEGER;
