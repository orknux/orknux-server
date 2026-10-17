-- See the Postgres V372: whether the person administered the installation the last time they signed in.
ALTER TABLE app_user ADD COLUMN resolved_admin BOOLEAN NOT NULL DEFAULT 0;
