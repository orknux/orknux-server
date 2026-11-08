-- The same as Postgres V673: pairing codes and the client credentials they are
-- traded for, both kept as hashes.
CREATE TABLE client_pairing_code (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username VARCHAR(120) NOT NULL,
    code_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX ux_client_pairing_code_hash ON client_pairing_code (code_hash);

CREATE TABLE client_credential (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username VARCHAR(120) NOT NULL,
    name VARCHAR(200) NOT NULL,
    client VARCHAR(40) NOT NULL,
    credential_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    last_used_at TIMESTAMP
);
CREATE UNIQUE INDEX ux_client_credential_hash ON client_credential (credential_hash);
CREATE INDEX ix_client_credential_username ON client_credential (username);
