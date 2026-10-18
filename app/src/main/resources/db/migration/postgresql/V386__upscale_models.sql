-- A provider of type STABLE_DIFFUSION_WEBUI - AUTOMATIC1111's WebUI, Forge or
-- SD.Next, by their shared /sdapi/v1 - and a model kind UPSCALE, which is what
-- such a provider holds: a picture made larger by an algorithm the server names.
-- And on a Design request, the algorithm and the scale for an upscale. Wider
-- CHECKs and nullable columns, so an older jar still starts.

ALTER TABLE model_provider DROP CONSTRAINT ck_model_provider_type;

ALTER TABLE model_provider
    ADD CONSTRAINT ck_model_provider_type
        CHECK (type IN ('OPENAI', 'ANTHROPIC', 'AZURE_OPENAI', 'OLLAMA', 'SYSTEM_ONE', 'LLAMA_CPP', 'STABLE_DIFFUSION_CPP',
                        'STABLE_DIFFUSION_WEBUI'));

ALTER TABLE llm_model DROP CONSTRAINT ck_llm_model_kind;

ALTER TABLE llm_model
    ADD CONSTRAINT ck_llm_model_kind CHECK (
        kind IN ('CHAT', 'EMBEDDING', 'COMPLETION', 'TRANSCRIPTION', 'SPEECH', 'IMAGE', 'DECISION', 'UPSCALE')
    );

ALTER TABLE design_image_job ADD COLUMN upscaler VARCHAR(255);
ALTER TABLE design_image_job ADD COLUMN scale DOUBLE PRECISION;
