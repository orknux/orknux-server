---
name: Box Long Commands
description: Load before you run a build, test suite or server in a box that takes more than a minute - start it in the background, follow it, stop it cleanly.
grant: box
---

# Long-running commands in a box

## Checklist

1. Start it: `orkbox_exec` with `wait: false` and a `timeout_seconds`. Keep the
   `execution` id it answers with.
2. Wait for it: `orkbox_execution` with that id and `wait_seconds` (say 120).
   Repeat until its status is no longer running.
3. Read the output: `orkbox_output`; pass the `nextOffset` it returns as
   `offset` to read on.
4. Report the outcome and how long it took; on failure, the failing part of
   the output.
5. A server or watcher you started: stop it with `orkbox_signal` (`TERM`, then
   `KILL`) before you finish.

## Builds and test suites

- Open the session with `caches` for the build tool (`maven`, `gradle`, `npm`)
  so dependencies are not downloaded every time.
- Quiet and offline flags keep runs short: `-q`, `--offline` once dependencies
  are cached.
- Do not start the same command twice. Check `orkbox_execution` first.

## Servers

- Start the server with `wait: false`.
- Wait until it answers, with a limit, rather than sleeping a fixed time:
  `for i in $(seq 30); do curl -sf http://localhost:8080/health && break; sleep 2; done`
- Talk to it with more `orkbox_exec` calls in the same session.

## Rules

- A session about to expire with work still running: `orkbox_extend` it.
- Never report a result you did not read in the output.
