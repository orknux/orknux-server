-- The questions a finished research suggests asking next, as a JSON list of
-- strings; how many it was asked for, fixed when it started; and the research
-- it was started from, where it is a follow-up of one - held by id without a
-- key, as a research's workflow is, so removing the parent leaves the child.
-- And a workspace's own number of follow-up questions; null is the
-- installation's research.followups.
--
-- New nullable columns: additive, so an older jar still starts.
ALTER TABLE research ADD COLUMN follow_ups TEXT;
ALTER TABLE research ADD COLUMN follow_up_count INTEGER;
ALTER TABLE research ADD COLUMN parent_research_id BIGINT;
ALTER TABLE workspace ADD COLUMN research_follow_ups INTEGER;
