-- Postgres V615. The agents a research was started with - Planner, Writer and
-- Reviewer - and what they were called then; the models columns stay, as the
-- models those agents had. Null on every research already there. Additive.
ALTER TABLE research ADD COLUMN planner_agent_id INTEGER;
ALTER TABLE research ADD COLUMN planner_agent_name VARCHAR(255);
ALTER TABLE research ADD COLUMN writer_agent_id INTEGER;
ALTER TABLE research ADD COLUMN writer_agent_name VARCHAR(255);
ALTER TABLE research ADD COLUMN reviewer_agent_id INTEGER;
ALTER TABLE research ADD COLUMN reviewer_agent_name VARCHAR(255);
