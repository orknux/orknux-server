-- What a connection is for, in a sentence. Postgres V366.
ALTER TABLE connection ADD COLUMN description VARCHAR(500);
ALTER TABLE workspace_connection ADD COLUMN description VARCHAR(500);
