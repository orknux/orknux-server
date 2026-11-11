-- The system a client runs on, for the Clients report's Computer column where the client names no computer. Additive.
ALTER TABLE client_use ADD COLUMN computer varchar(120);
