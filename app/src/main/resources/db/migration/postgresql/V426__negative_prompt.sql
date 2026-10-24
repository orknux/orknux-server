-- A negative prompt: what a drawn picture must not contain, sent beside the
-- prompt to the servers that take one - stable-diffusion.cpp's and a Stable
-- Diffusion WebUI's. Issue #657.
--
-- llm_model.negative_prompt is a model's own default, sent when a drawing names
-- none; design_image_job.negative_prompt is what one picture on Design's Images
-- page was asked with. Both nullable: null is none. Additive: an older jar
-- never reads them.
ALTER TABLE llm_model ADD COLUMN negative_prompt TEXT;
ALTER TABLE design_image_job ADD COLUMN negative_prompt TEXT;
