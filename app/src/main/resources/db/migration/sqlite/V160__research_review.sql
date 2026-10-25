-- See the Postgres V480: a research's review, and a workspace's own number of review rounds.
ALTER TABLE research ADD COLUMN review BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE research ADD COLUMN reviewer_model_id INTEGER;
ALTER TABLE research ADD COLUMN reviewer_model_name VARCHAR(255);
ALTER TABLE research ADD COLUMN review_rounds TEXT;
ALTER TABLE workspace ADD COLUMN research_review_rounds INTEGER;
