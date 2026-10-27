-- Postgres V635: the installation's own mail server, set in Admin Settings -> Mail.

CREATE TABLE installation_mail
(
    id               integer not null primary key,
    host             varchar(255) not null,
    port             integer,
    username         varchar(255),
    password         varchar(4000),
    from_address     varchar(320) not null,
    security         varchar(16) not null default 'STARTTLS',
    last_modified_at timestamp not null default CURRENT_TIMESTAMP,
    last_modified_by varchar(120) not null default 'orknux',
    constraint ck_installation_mail_single CHECK (id = 1),
    constraint ck_installation_mail_security CHECK (security IN ('NONE', 'STARTTLS', 'TLS'))
);
