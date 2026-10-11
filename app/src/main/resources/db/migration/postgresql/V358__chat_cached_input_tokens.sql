-- How many of a chat's input tokens the provider reused from its cache.
-- Every round sends the whole conversation, so input summed over a long session
-- read as millions of tokens a local server mostly never read again. Issue #627.
-- Additive: an older jar does not know the column.

ALTER TABLE chat_session ADD COLUMN spent_cached_input_tokens BIGINT NOT NULL DEFAULT 0;
