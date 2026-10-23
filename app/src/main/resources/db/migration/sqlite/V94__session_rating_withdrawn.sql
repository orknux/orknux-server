-- See the Postgres V416: a review taken back is marked, not deleted.
ALTER TABLE session_rating ADD COLUMN withdrawn_at TIMESTAMP;
