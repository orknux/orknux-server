-- Whether a video attached to a chat message is sent to this model as a
-- video_url part of the turn.
--
-- A switch on the model rather than something detected: nothing in the OpenAI
-- shape says whether a model watches video, and a server that cannot decode the
-- part fails the request rather than ignoring it. Off, so every model that
-- exists refuses a video in a sentence until somebody says it takes one.
-- Additive: an older jar never reads it.
ALTER TABLE llm_model ADD COLUMN takes_video BOOLEAN NOT NULL DEFAULT FALSE;
