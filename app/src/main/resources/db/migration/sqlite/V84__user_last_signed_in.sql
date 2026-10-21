-- See the Postgres V406: when somebody last signed in, null until they do.
ALTER TABLE app_user ADD COLUMN last_signed_in_at TIMESTAMP;
