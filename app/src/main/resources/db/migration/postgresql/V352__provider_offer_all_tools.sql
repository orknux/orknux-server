-- Whether a provider's agents are handed every tool from their first round,
-- and the most tools one request to it may carry.
--
-- A local server reuses the part of the prompt a new request begins with, and
-- the tools sit near the top of it. Loading or dropping a tool part way through
-- a turn changed that beginning, so llama.cpp read a long session again after
-- every tool_load. Offered all at once, the list never moves.
--
-- Both null for every row: null is the default for the provider's type - every
-- tool up front on Ollama and OpenAI (whose type also covers OpenAI-compatible
-- servers such as llama.cpp), the search beyond the agent's own ceiling on the
-- rest, and the type's own cap on how many go in one request.
--
-- Additive: an older jar does not know the columns.

ALTER TABLE model_provider ADD COLUMN offer_all_tools BOOLEAN;
ALTER TABLE model_provider ADD COLUMN max_tools INTEGER;
