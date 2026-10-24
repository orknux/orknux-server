-- Whether a research searches the web. Issue #659.
--
-- Off, it reads only the documents picked and the pages of the websites it was
-- told to keep to. On is what every research did before there was a choice,
-- which is what the default gives the rows already here. A new column with a
-- default: additive, so an older jar still starts.
ALTER TABLE research ADD COLUMN use_web_search BOOLEAN NOT NULL DEFAULT TRUE;
