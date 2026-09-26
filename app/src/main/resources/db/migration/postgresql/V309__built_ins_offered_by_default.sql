-- Every built-in is offered, and hiding one is a decision a workspace has to
-- take deliberately. Issues #482 and #483.
--
-- V303 preserved what each agent had at the time, which meant a hidden list on
-- almost every agent - and the tools the server brings are what the product is
-- built on. An agent without a scratchpad retypes a file it could have kept;
-- one that cannot say it has finished answers in prose and is asked again; one
-- without a clock invents today's date. None of that is behaviour this product
-- can stand behind, and all of it looked like the model being poor.
--
-- So the hidden lists are emptied: every agent offers every built-in, which is
-- what "built-in" was supposed to mean. An installation that had deliberately
-- taken draw_picture away from an agent will find it offered again, and can
-- hide it once the switch below is on for that workspace.
--
-- And hiding is gated. `unsafe_built_in_tools` is off everywhere; while it is
-- off, a save that would hide a built-in is refused rather than quietly
-- ignored. What the switch opens is the ability to switch, which is why it is
-- named the way it is.

DELETE FROM agent_hidden_tool;

ALTER TABLE workspace
    ADD COLUMN unsafe_built_in_tools boolean NOT NULL DEFAULT false;
