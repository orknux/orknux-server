-- See the Postgres V460: the runs a research had before the one it is on now.
ALTER TABLE research ADD COLUMN earlier_runs TEXT;
