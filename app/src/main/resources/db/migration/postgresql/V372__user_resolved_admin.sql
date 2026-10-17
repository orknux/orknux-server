-- Whether the person administered the installation the last time they signed
-- in. The admin authority may come from the directory alone - ORKNUX_ADMIN_ROLE
-- names a group, and no role row here corresponds to it - so it is not among
-- resolved_role_ids and has to be kept beside them.
ALTER TABLE app_user ADD COLUMN resolved_admin BOOLEAN NOT NULL DEFAULT FALSE;
