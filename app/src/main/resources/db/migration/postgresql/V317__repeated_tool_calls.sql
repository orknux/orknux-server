-- How many times running an agent may make the same call. Issue #516.
--
-- Seen in session 470: an agent reasoned its way to the right answer - it had
-- picked the tool, the chart kind and the data - then second-guessed itself
-- with "Wait, I should check if there are any specific preferences", called
-- `todo_list` and `current_time`, and did that a hundred and fifty-four more
-- times without ever drawing the chart.
--
-- Both are pure reads. Identical arguments give identical results, so the
-- context on the next turn is the one that produced the call, and nothing
-- inside the cycle can break it. The rounds ceiling eventually stopped it, but
-- a ceiling only bounds total work: it cannot tell three hundred rounds of
-- progress from one round repeated three hundred times, so a loop spends the
-- entire budget before anything notices.
--
-- Null means the installation's number, the way every other per-workspace
-- ceiling here works.

-- And a window, because repetition on its own is not the fault.
--
-- An agent asked to watch something checks it, waits, and checks it again -
-- the same call, the same arguments, the same answer, and entirely correct. A
-- count alone cannot tell that from a loop; what separates them is how close
-- together the calls are. Three identical calls in four seconds is a cycle;
-- three across an hour is somebody keeping an eye on things.

-- And how many times a turn is told before it is ended, because that is policy
-- too: once is a warning worth giving, and how much patience an installation
-- has for a model that ignores it is not this code's decision to make.

ALTER TABLE workspace
    ADD COLUMN max_repeated_tool_calls integer,
    ADD COLUMN repeated_tool_calls_window_seconds integer,
    ADD COLUMN repeated_tool_call_warnings integer;

COMMENT ON COLUMN workspace.max_repeated_tool_calls IS
    'How many identical tool calls in a row end the turn; null takes the installation''s number.';

COMMENT ON COLUMN workspace.repeated_tool_calls_window_seconds IS
    'They only count as repetition inside this many seconds; null takes the installation''s number.';

COMMENT ON COLUMN workspace.repeated_tool_call_warnings IS
    'How many times a looping turn is told before it is ended; null takes the installation''s number.';
