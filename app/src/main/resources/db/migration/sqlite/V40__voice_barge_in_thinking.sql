-- Whether talking while the model is still thinking stops it; null is on.
-- Postgres V363. A numbered migration and not the baseline: an installation
-- past V1 never reads the baseline again, and refuses to start once it changes.
ALTER TABLE workspace ADD COLUMN voice_barge_in_thinking BOOLEAN;
