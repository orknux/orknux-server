-- Why the launcher left a chosen release unstarted - one built for a newer Java
-- than this runtime carries - so the Updates page can say so. Null while it runs.
ALTER TABLE server_release ADD COLUMN held_back VARCHAR(500);
