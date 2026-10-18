-- A provider of type STABLE_DIFFUSION_CPP: stable-diffusion.cpp's server, spoken
-- to as OpenAI is, and kept apart for how it reads a picture edit's mask - white
-- for the change, where the standard marks it transparent. And edit_mask, the
-- provider's own say in that; null is its type's. A wider CHECK, so an older jar
-- still starts.

ALTER TABLE model_provider ADD COLUMN edit_mask VARCHAR(20);

ALTER TABLE model_provider DROP CONSTRAINT ck_model_provider_type;

ALTER TABLE model_provider
    ADD CONSTRAINT ck_model_provider_type
        CHECK (type IN ('OPENAI', 'ANTHROPIC', 'AZURE_OPENAI', 'OLLAMA', 'SYSTEM_ONE', 'LLAMA_CPP', 'STABLE_DIFFUSION_CPP'));
