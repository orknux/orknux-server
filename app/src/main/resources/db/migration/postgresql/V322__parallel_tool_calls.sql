-- Whether one reply from a model may ask for several tools at once. Issue #530.
--
-- Nothing was sent until now, so llama.cpp's Gemma 4 grammar allowed unlimited
-- calls per reply, and at temperature 1.0 a model that had written three calls
-- found repeating them the likeliest continuation. One reply held 151 copies of
-- the same call before the output limit cut it off.
--
-- Null sends nothing and the provider decides, which is how every model behaved
-- before this. False is one call per reply.

ALTER TABLE llm_model
    ADD COLUMN parallel_tool_calls boolean;

COMMENT ON COLUMN llm_model.parallel_tool_calls IS
    'Whether one reply may ask for several tools; null lets the provider decide, false is one per reply.';
