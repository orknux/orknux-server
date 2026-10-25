-- See the Postgres V482: a research's follow-up questions, and the research it was started from.
ALTER TABLE research ADD COLUMN follow_ups TEXT;
ALTER TABLE research ADD COLUMN follow_up_count INTEGER;
ALTER TABLE research ADD COLUMN parent_research_id INTEGER;
ALTER TABLE workspace ADD COLUMN research_follow_ups INTEGER;
