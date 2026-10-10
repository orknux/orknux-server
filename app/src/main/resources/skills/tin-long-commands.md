---
name: Tin Long Commands
description: Run builds, test suites and servers that take a while in a tin - start them in the background, follow them, stop them cleanly.
---

# Long-running commands in a tin

## Objective

A full build, a test suite or a server can run for minutes or forever. Run
them without blocking your turn, keep track of them, and never leave them
running when you are done.

## Builds and test suites

- Start with `tin_exec` and `wait: false`. You get an `execution` id back
  straight away.
- Follow it with `tin_execution` and `wait_seconds` (wait up to that long for
  it to finish), and read what it printed so far with `tin_output` - pass the
  `nextOffset` it returns as `offset` to read on.
- Give a sensible `timeout_seconds` so a hung command is stopped for you.
- Use the dependency `caches` on `tin_open` and offline or quiet flags
  (`-q`, `--offline` when dependencies are cached) to keep runs short.

## Servers and watchers

- Start the server with `wait: false`, then poll until it answers (for
  example `curl -sf http://localhost:<port>/health` in a short loop with a
  limit), rather than sleeping a fixed time.
- Talk to it from the same session with further `tin_exec` calls.
- Stop it with `tin_signal` (`TERM`, then `KILL` if it does not exit) when you
  are finished.

## Rules

- Do not start the same long command twice; check `tin_execution` first.
- If a session is about to expire with work still running, `tin_extend` it.
- Report how long the run took and its outcome, with the failing part of the
  output when it failed.
