-- A workspace's web search key may be read from a workspace variable secret,
-- the same per-field choice every other credential has had since #244: its own
-- encrypted copy in `api_key`, or a reference by id beside it, never both.
-- A nullable column and a CHECK every existing row already meets: additive.
ALTER TABLE workspace_search
    ADD COLUMN api_key_variable_id BIGINT;

ALTER TABLE workspace_search
    ADD CONSTRAINT ck_workspace_search_api_key
        CHECK (api_key_variable_id IS NULL OR api_key IS NULL);
