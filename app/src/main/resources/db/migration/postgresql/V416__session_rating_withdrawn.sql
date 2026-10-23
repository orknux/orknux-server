-- A review taken back is kept, marked, rather than deleted: the chat and the
-- session say it was taken back, and the reports and the per-conversation limit
-- leave it out. Additive.
ALTER TABLE session_rating ADD COLUMN withdrawn_at TIMESTAMPTZ;
