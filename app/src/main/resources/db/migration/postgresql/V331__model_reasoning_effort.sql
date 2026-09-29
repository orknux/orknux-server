-- How hard a reasoning model thinks before it answers: Azure OpenAI's o-series
-- and GPT-5 family take `reasoning_effort` (minimal, low, medium, high).
--
-- Null sends nothing and the deployment's own default applies, which is how
-- every model behaved before this. Which values are allowed, and on which
-- provider type, is declared in code (ChatParameters) rather than by a CHECK:
-- a provider that takes the parameter later is one entry there, not a
-- migration on both engines.

ALTER TABLE llm_model
    ADD COLUMN reasoning_effort varchar(16);

COMMENT ON COLUMN llm_model.reasoning_effort IS
    'How hard a reasoning model thinks; null sends nothing. Allowed values per provider type are declared in ChatParameters.';
