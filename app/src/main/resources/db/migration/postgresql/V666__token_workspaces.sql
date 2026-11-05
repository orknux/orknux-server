-- A token's own list of the workspaces it may reach, as a JSON array of ids.
-- Null is every workspace its owner may see, as before. Additive: an older jar ignores it.
ALTER TABLE app_user_token ADD COLUMN workspaces TEXT;
