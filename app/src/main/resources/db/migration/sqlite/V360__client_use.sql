-- Who uses which Orknux client, for Admin > Reports. The same as Postgres V680. Additive.
CREATE TABLE client_use
(
    id         INTEGER      PRIMARY KEY AUTOINCREMENT,
    username   varchar(120) NOT NULL,
    kind       varchar(16)  NOT NULL,
    client     varchar(64),
    name       varchar(200) NOT NULL,
    version    varchar(64),
    first_seen TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_client_use UNIQUE (username, kind, name)
);
CREATE INDEX idx_client_use_seen ON client_use (last_seen);
