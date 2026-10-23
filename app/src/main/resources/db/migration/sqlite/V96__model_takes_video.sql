-- Whether a video attached to a chat is sent to this model; see the Postgres V418.
ALTER TABLE llm_model ADD COLUMN takes_video boolean NOT NULL DEFAULT false;
