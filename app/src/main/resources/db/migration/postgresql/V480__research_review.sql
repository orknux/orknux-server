-- A research may be reviewed before it is saved: whether it was asked to be,
-- the chat model the reviewer thinks with (by id and, as the mode and model are,
-- by name), and what each round of review said, as a JSON list written by the
-- Review step. And a workspace's own number of review rounds, where it has one;
-- null is the installation's research.review.rounds.
--
-- New columns, one with a default and the rest nullable: additive, so an older
-- jar still starts.
ALTER TABLE research ADD COLUMN review BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE research ADD COLUMN reviewer_model_id BIGINT;
ALTER TABLE research ADD COLUMN reviewer_model_name VARCHAR(255);
ALTER TABLE research ADD COLUMN review_rounds TEXT;
ALTER TABLE workspace ADD COLUMN research_review_rounds INTEGER;
