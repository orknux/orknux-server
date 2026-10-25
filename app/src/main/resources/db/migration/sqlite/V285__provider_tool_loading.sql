-- Postgres V605. join_loaded_tools: whether a tool loaded with tool_load joins
-- the tool array at the next turn; null is the type's default, off for
-- LLAMA_CPP and on for the rest. offer_all_tools comes back into use, off by
-- default, and every existing row is set to false. Additive.
ALTER TABLE model_provider ADD COLUMN join_loaded_tools BOOLEAN;
UPDATE model_provider SET offer_all_tools = 0;
