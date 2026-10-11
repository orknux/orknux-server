-- Whether a provider's agents are handed every tool from their first round,
-- and the most tools one request to it may carry. The postgres copy, V352,
-- carries the reasoning.

ALTER TABLE model_provider ADD COLUMN offer_all_tools BOOLEAN;
ALTER TABLE model_provider ADD COLUMN max_tools INTEGER;
