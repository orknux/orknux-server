-- The tools an agent found, kept with the conversation that found them.

-- An agent granted more tools than the provider accepts is handed the ones it
-- uses constantly and `find_tools`, and what it searches for is put in the array
-- for the next round. Without this the search is good for one turn: the agent
-- asked a follow-up would rediscover the tool it used a minute ago, spending a
-- round per turn on a question it has already answered - and a second search is
-- not guaranteed to return what the first did, so what the agent believes it
-- can do would change under it between turns.

-- One column holding the names rather than a table of them, because it is read
-- and written whole and nothing ever asks it a question: it is this session's
-- list, wanted entire and only ever by the session.
ALTER TABLE llm_session ADD COLUMN found_tools text;
