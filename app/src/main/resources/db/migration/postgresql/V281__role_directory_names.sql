-- Which of the directory's names grant a role, said here rather than in a file.

-- A role is matched to a group by name: a role called `Backend` is granted to
-- whoever holds ROLE_BACKEND, which is what the directory sends. That works
-- until a group is called `dev.TL` or `BoarCMS Group`, and then the only way to
-- grant it was `orknux.security.role-mapping` in the configuration file - which
-- an administrator cannot reach, cannot see, and cannot change without somebody
-- redeploying the installation for them.

-- So the mapping moves to where the role is. Each row is one name the provider
-- may use for this role: an LDAP group's common name or full DN, or the value of
-- an OIDC claim. The configured mapping still works and still wins nothing - the
-- two are read together, because an installation that has one in its file should
-- not lose it by upgrading.
CREATE TABLE security_role_match
(
    role_id BIGINT       NOT NULL REFERENCES security_role (id) ON DELETE CASCADE,
    value   VARCHAR(500) NOT NULL,
    PRIMARY KEY (role_id, value)
);
