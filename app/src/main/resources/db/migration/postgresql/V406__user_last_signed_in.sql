-- When somebody last came in through the front door: a directory, OIDC, or a
-- password this installation holds. Null until their next sign-in; an API token
-- is not an arrival and does not move it.
ALTER TABLE app_user ADD COLUMN last_signed_in_at TIMESTAMPTZ;
