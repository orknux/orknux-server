-- Two provider settings about how an agent reaches the tools it does not
-- carry from the start.
--
-- join_loaded_tools: whether a tool loaded with tool_load joins the
-- conversation's tool array at the next turn. Null is the type's default -
-- off for LLAMA_CPP, whose chat template writes the tools at the head of the
-- prompt so every turn after a load re-reads the whole conversation, and on
-- for the rest (ProviderType.joinsLoadedTools). Every existing provider is
-- left null and so takes its type's default.
--
-- offer_all_tools comes back into use: every tool the agent holds goes out
-- from its first request, up to the provider's cap. 1.1.9 read null as on for
-- OPENAI, OLLAMA and LLAMA_CPP; it is off by default now, and every row is set
-- to false so no provider starts carrying everything on an upgrade - and so a
-- rollback to 1.1.9, which would read null as on, reads it as off too.
--
-- Additive: a nullable column, and a value an older jar reads.
ALTER TABLE model_provider ADD COLUMN join_loaded_tools BOOLEAN;
UPDATE model_provider SET offer_all_tools = FALSE;
