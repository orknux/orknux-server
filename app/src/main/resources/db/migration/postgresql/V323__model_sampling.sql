-- How a model picks its words, set on the model. Issue #533.
--
-- Nothing was sent until now, so every model ran on its server's defaults - a
-- local Gemma at the temperature 1.0 stored in its model file, with no repeat
-- penalty, without anybody choosing it.
--
-- Each is null until set, and null sends nothing, so an existing model behaves
-- exactly as it did. Top-k, min-p and the repeat penalty are for servers that
-- take them; a hosted OpenAI model refuses them, which is why each is optional.

ALTER TABLE llm_model
    ADD COLUMN temperature double precision,
    ADD COLUMN top_p double precision,
    ADD COLUMN top_k integer,
    ADD COLUMN min_p double precision,
    ADD COLUMN repeat_penalty double precision;
