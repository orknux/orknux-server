-- Rate throttling per model, so a run holds itself under a provider's limit
-- rather than being turned away with a 429. The provider carries the defaults;
-- a model overrides them. Distinct from the token_limit / requests_per_minute
-- spending caps, which reset - this is a rate to stay under. Issue #426.

ALTER TABLE model_provider ADD COLUMN throttle_tokens_per_second bigint;
ALTER TABLE model_provider ADD COLUMN throttle_requests_per_second double precision;
ALTER TABLE model_provider ADD COLUMN accept_retry_after boolean NOT NULL DEFAULT true;

-- Null on a model inherits the provider's default; 0 turns the dimension off.
ALTER TABLE llm_model ADD COLUMN throttle_tokens_per_second bigint;
ALTER TABLE llm_model ADD COLUMN throttle_requests_per_second double precision;
ALTER TABLE llm_model ADD COLUMN accept_retry_after boolean;
