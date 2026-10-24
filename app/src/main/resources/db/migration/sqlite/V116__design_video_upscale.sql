-- Postgres V438: a video made larger on Design's Videos page, kept as a version
-- of the video it was made from, with how far its frames have got.

ALTER TABLE design_video ADD COLUMN version_of INTEGER;
ALTER TABLE design_video ADD COLUMN upscaled_from INTEGER;
ALTER TABLE design_video ADD COLUMN upscaler VARCHAR(255);
ALTER TABLE design_video ADD COLUMN scale DOUBLE PRECISION;
ALTER TABLE design_video ADD COLUMN frames_done INTEGER;
ALTER TABLE design_video ADD COLUMN frames_total INTEGER;
