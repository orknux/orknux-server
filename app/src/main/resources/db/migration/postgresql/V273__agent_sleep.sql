-- An agent that stopped its turn to wait, and what it left itself.
--
-- `finish_answer` ends a turn; with a wake-up it parks the step instead, and
-- the run comes back to the same node when the time is up. Two things have to
-- outlive the worker that started the wait: how many times in a row this step
-- has done it, which is what the installation's ceiling is counted against, and
-- the note the agent left - a woken node is handed its input again and nothing
-- else, so without the note it wakes with no idea why it slept.
ALTER TABLE execution_step ADD COLUMN agent_sleeps integer NOT NULL DEFAULT 0;
ALTER TABLE execution_step ADD COLUMN agent_sleep_note text;
