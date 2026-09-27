-- How many tool calls one message may ask for. Issue #518.
--
-- Session 474 arrived as a hundred and forty-one `skill_load` calls inside a
-- single assistant message: one thinking event, then the same call over and
-- over, the first of them with its arguments cut off mid-JSON. That is not a
-- plan with a hundred and forty-one steps in it, it is a decode that has come
-- apart, and llama.cpp said so in its own log - "failed to parse tool call
-- arguments as JSON".
--
-- The repetition guard cannot catch it. That one counts identical calls
-- between rounds, and this is all inside one round: by the time it has
-- anything to say, the whole batch has already been asked for.
--
-- Null means the installation's number, the way every other per-workspace
-- ceiling here works.

ALTER TABLE workspace
    ADD COLUMN max_tool_calls_at_once integer;

COMMENT ON COLUMN workspace.max_tool_calls_at_once IS
    'How many tool calls one message may ask for; null takes the installation''s number.';
