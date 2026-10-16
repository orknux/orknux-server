-- What a connection is for, in a sentence: shown under its name and on hover,
-- and told to an agent beside its name. On the admin default and on every
-- workspace's copy, which follows the default's like the name does. Nullable,
-- so rollback-floor stays where it is.
ALTER TABLE connection ADD COLUMN description VARCHAR(500);
ALTER TABLE workspace_connection ADD COLUMN description VARCHAR(500);
