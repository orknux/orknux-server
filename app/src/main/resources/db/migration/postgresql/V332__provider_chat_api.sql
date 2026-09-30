-- Which OpenAI API an Azure OpenAI provider's chats are sent through.
--
-- Azure's chat completions refuse a reasoning model its tools ("Function tools
-- with reasoning_effort are not supported ... use /v1/responses"), so an Azure
-- provider now speaks the Responses API by default, and every existing one is
-- moved onto it here - that is the fix those installations need. CHAT_COMPLETIONS
-- is the old road, chosen on the provider's page to go back to it.
--
-- Null on every other type, which speaks chat completions only; null on an
-- Azure provider is read as RESPONSES. The values are the ChatApi enum's, and
-- are checked in code like reasoning_effort (V331) rather than by a CHECK.

ALTER TABLE model_provider
    ADD COLUMN chat_api varchar(24);

UPDATE model_provider SET chat_api = 'RESPONSES' WHERE type = 'AZURE_OPENAI';

COMMENT ON COLUMN model_provider.chat_api IS
    'Azure OpenAI only: RESPONSES (default) or CHAT_COMPLETIONS. Null on every other type.';
