-- A workspace's own tool rounds; see the Postgres V402.
ALTER TABLE workspace ADD COLUMN chat_max_rounds INTEGER
    CONSTRAINT ck_workspace_chat_max_rounds CHECK (chat_max_rounds IS NULL OR (chat_max_rounds BETWEEN 2 AND 10000));
