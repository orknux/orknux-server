-- What a person held when they paired a client: for a directory account its groups,
-- which nothing stores, so its client could otherwise see no workspace. The same as Postgres V674. Additive.
ALTER TABLE client_pairing_code ADD COLUMN authorities VARCHAR(4000);
ALTER TABLE client_credential ADD COLUMN authorities VARCHAR(4000);
