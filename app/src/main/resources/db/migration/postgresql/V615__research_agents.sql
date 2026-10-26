-- A research runs with agents rather than models. Issue #678.
--
-- The New research form picks a Planner, a Writer and a Reviewer agent, and
-- each thinks with the model on its own page; the research keeps which agents
-- it was started with, and what they were called then, for the reason it keeps
-- its mode's name. model_id and reviewer_model_id stay and are still written:
-- the models those agents had when it started, which the estimates and the
-- follow-up questions read. No foreign keys: an agent removed since leaves the
-- name it had. Additive: null on every research already there, which ran with
-- the models it names.

ALTER TABLE research ADD COLUMN planner_agent_id bigint;
ALTER TABLE research ADD COLUMN planner_agent_name varchar(255);
ALTER TABLE research ADD COLUMN writer_agent_id bigint;
ALTER TABLE research ADD COLUMN writer_agent_name varchar(255);
ALTER TABLE research ADD COLUMN reviewer_agent_id bigint;
ALTER TABLE research ADD COLUMN reviewer_agent_name varchar(255);
