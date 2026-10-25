-- A file attached to a chat is held in the session's store by reference.
--
-- It was copied in as base64 JSON, so a file over Session Value Size - a 28 MB
-- PDF against the 8 MB default - was given no key at all, and the agent went
-- looking for it by name on machines it could reach. A row that names an
-- attachment holds no value of its own: the store reads the bytes from the
-- attachment store when a tool asks for them. No foreign key: a reference to an
-- attachment since removed reads as nothing kept, which is what it is.
-- Additive: null on every row already there, which hold their own values.

ALTER TABLE llm_session_store ADD COLUMN attachment_id bigint;
