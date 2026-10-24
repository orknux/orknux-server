-- See the Postgres V434: the web search key read from a workspace variable secret, or its own copy, never both.
ALTER TABLE workspace_search ADD COLUMN api_key_variable_id INTEGER
    CONSTRAINT ck_workspace_search_api_key CHECK (api_key_variable_id IS NULL OR api_key IS NULL);
