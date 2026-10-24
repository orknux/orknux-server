-- See the Postgres V431: whether a research searches the web. Issue #659.
ALTER TABLE research ADD COLUMN use_web_search BOOLEAN NOT NULL DEFAULT TRUE;
