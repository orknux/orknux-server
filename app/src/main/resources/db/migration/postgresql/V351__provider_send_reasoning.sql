-- Whether a provider is sent the model's own earlier thinking.
--
-- A reasoning model's thinking was echoed back on every later request - as
-- `reasoning_content` on an assistant turn that asked for tools, and as the
-- Responses API's reasoning items - so the model could see the plan behind its
-- own last call (#532). Some providers want that. Others drop it: llama.cpp's
-- Gemma template renders old turns without their thoughts, so echoing them
-- changes only the text of the prompt and buys nothing but a lost cache.
--
-- False for every row, existing ones included: an installation is no longer
-- sent the echo unless somebody turns it on for the provider that needs it.
-- The thinking is still captured, stored and shown; only the wire changes, so
-- turning it on later applies to sessions that already ran.
--
-- Additive: an older jar does not know the column and the default fills it.

ALTER TABLE model_provider ADD COLUMN send_reasoning BOOLEAN NOT NULL DEFAULT FALSE;
