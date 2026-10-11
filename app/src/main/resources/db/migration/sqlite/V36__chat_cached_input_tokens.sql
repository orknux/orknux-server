-- Postgres V358: how many of a chat's input tokens the provider reused from its cache. Issue #627.

ALTER TABLE chat_session ADD COLUMN spent_cached_input_tokens integer NOT NULL DEFAULT 0;
