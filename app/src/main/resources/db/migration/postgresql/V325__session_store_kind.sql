-- What a session store value is, recorded by whoever puts it. Issue #559.
--
-- A reader handed a key could only guess whether it held a page or a PDF, and
-- guessed from the value: a PDF went to Slack as a text file of base64. Both
-- columns are null on the rows already there, which is the truth about them -
-- nobody said - and readers fall back to guessing only for those.

ALTER TABLE llm_session_store ADD COLUMN content_type varchar(255);
ALTER TABLE llm_session_store ADD COLUMN is_binary boolean;
