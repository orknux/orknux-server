-- A video made larger on Design's Videos page: its frames upscaled by an
-- UPSCALE model's WebUI and put back together, kept as a version of the video
-- it was made from rather than in its place.
--
-- version_of is the video it is a version of - the first one, as a picture's
-- versions all point at the picture - and upscaled_from the one whose frames
-- were taken, which differs when a version is upscaled again. upscaler and
-- scale are what it was asked with, and frames_done of frames_total is how far
-- it has got, which is what the editor's progress is counted from.
--
-- Nullable columns on an existing table: additive, so an older jar still starts.

ALTER TABLE design_video ADD COLUMN version_of BIGINT;
ALTER TABLE design_video ADD COLUMN upscaled_from BIGINT;
ALTER TABLE design_video ADD COLUMN upscaler VARCHAR(255);
ALTER TABLE design_video ADD COLUMN scale DOUBLE PRECISION;
ALTER TABLE design_video ADD COLUMN frames_done INTEGER;
ALTER TABLE design_video ADD COLUMN frames_total INTEGER;
