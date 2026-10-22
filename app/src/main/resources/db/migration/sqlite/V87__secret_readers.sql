-- See the Postgres V409: SECRETS beside ADMIN and USER. SQLite cannot change a
-- CHECK in place, so the table is built again with the wider one. Nothing
-- points at it, so dropping the old one cascades nothing.
CREATE TABLE security_role_scope_new
(
    role_id                      integer not null,
    scope                        varchar(16) not null,
    primary key (role_id, scope),
    constraint ck_security_role_scope CHECK (((scope) IN ('ADMIN', 'USER', 'SECRETS'))),
    constraint security_role_scope_role_id_fkey FOREIGN KEY (role_id) REFERENCES security_role(id) ON DELETE CASCADE
);

INSERT INTO security_role_scope_new (role_id, scope) SELECT role_id, scope FROM security_role_scope;

DROP TABLE security_role_scope;

ALTER TABLE security_role_scope_new RENAME TO security_role_scope;
