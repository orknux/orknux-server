-- Why the launcher left a chosen release unstarted; see the Postgres V376.
ALTER TABLE server_release ADD COLUMN held_back VARCHAR(500);
