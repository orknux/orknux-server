-- Whether an access token may only read. Postgres V368.
ALTER TABLE app_user_token ADD COLUMN read_only BOOLEAN NOT NULL DEFAULT 0;
