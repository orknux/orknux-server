-- A provider of type LLAMA_CPP: a llama.cpp server, spoken to as OpenAI is,
-- and asked as well for how far it has got reading a prompt, which no other
-- server reports. Issue #627. A wider CHECK, so an older jar still starts.

ALTER TABLE model_provider DROP CONSTRAINT ck_model_provider_type;

ALTER TABLE model_provider
    ADD CONSTRAINT ck_model_provider_type
        CHECK (type IN ('OPENAI', 'ANTHROPIC', 'AZURE_OPENAI', 'OLLAMA', 'SYSTEM_ONE', 'LLAMA_CPP'));
