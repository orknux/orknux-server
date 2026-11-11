-- The system a client runs on, for the Clients report. The same as Postgres V681. Additive.
ALTER TABLE client_use ADD COLUMN computer varchar(120);
