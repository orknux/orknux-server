-- A role may read a workspace's secrets back: SECRETS beside ADMIN and USER.
-- Wider only, so an older server still starts on it; a role holding SECRETS is
-- what it would not know.
ALTER TABLE security_role_scope DROP CONSTRAINT ck_security_role_scope;
ALTER TABLE security_role_scope ADD CONSTRAINT ck_security_role_scope CHECK (scope IN ('ADMIN', 'USER', 'SECRETS'));
