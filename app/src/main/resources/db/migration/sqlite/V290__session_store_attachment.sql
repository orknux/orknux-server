-- Postgres V610. attachment_id: a chat attachment held in the session's store
-- by reference, its bytes read from the attachment store rather than copied in
-- as base64 JSON. Null on every row already there. Additive.
ALTER TABLE llm_session_store ADD COLUMN attachment_id INTEGER;
