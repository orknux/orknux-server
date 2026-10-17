-- See the Postgres V371: the roles a person held the last time they signed in.
ALTER TABLE app_user ADD COLUMN resolved_role_ids TEXT;
