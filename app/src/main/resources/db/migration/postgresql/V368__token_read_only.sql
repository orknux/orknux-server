-- Whether an access token may only read. Existing tokens keep reading and
-- writing, which is what they did; a column with a default, so rollback-floor stays.
ALTER TABLE app_user_token ADD COLUMN read_only BOOLEAN NOT NULL DEFAULT FALSE;
