-- An agent may be given more rounds of tool calls than the installation allows.

-- A round is one call to the model: it answers, or it asks for tools and what it
-- asks for is run and handed back. The ceiling was eight, written into the code,
-- and eight is right for an agent with two tools and wrong for one holding
-- twenty - a list, a load and a lookup is three rounds before the work starts.
-- What happened instead was "kept looking things up without reaching an answer",
-- with everything the agent had gathered thrown away.

-- Null is the installation's own number, which is where every agent starts and
-- where most of them stay. This column is for the one agent whose work is longer
-- than the rest, without raising the ceiling for everything else on the server.

ALTER TABLE agent
    ADD COLUMN max_rounds INTEGER;
