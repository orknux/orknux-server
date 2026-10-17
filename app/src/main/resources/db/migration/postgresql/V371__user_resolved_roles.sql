-- The roles a person held the last time they signed in, by id, as the directory
-- or the identity provider resolved them. Stored roles (app_user_role) are what
-- an administrator gave somebody here; this is what their groups gave them,
-- which was never written down - so a workspace could not list the people its
-- roles let in unless each had been given a role by hand. Nullable: nothing is
-- known about somebody who has not signed in since.
ALTER TABLE app_user ADD COLUMN resolved_role_ids TEXT;
