-- The installation's own mail server, set in Admin Settings -> Mail.
--
-- One row or none. None means the ORKNUX_MAIL_* environment is what the server
-- sends through, which is what every installation did before this; a row wins
-- over it. Not a workspace's connection, and nothing points here from one.
--
-- `password` holds an encrypted envelope, not a password: it goes through the
-- converter every stored credential does, hence the width.

CREATE TABLE installation_mail (
    id               bigint        NOT NULL PRIMARY KEY,
    host             varchar(255)  NOT NULL,
    port             integer,
    username         varchar(255),
    password         varchar(4000),
    from_address     varchar(320)  NOT NULL,
    security         varchar(16)   NOT NULL DEFAULT 'STARTTLS',
    last_modified_at timestamptz   NOT NULL DEFAULT now(),
    last_modified_by varchar(120)  NOT NULL DEFAULT 'orknux',
    CONSTRAINT ck_installation_mail_single CHECK (id = 1),
    CONSTRAINT ck_installation_mail_security CHECK (security IN ('NONE', 'STARTTLS', 'TLS'))
);

COMMENT ON COLUMN installation_mail.password IS
    'Encrypted with the installation secret key; never read back to a screen.';
