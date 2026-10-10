---
name: Tins
description: Run commands and work with files in a sandbox - build a project, run its tests, try a script - with the tin_ tools.
---

# Tins

## Objective

A tin is a sandbox you can open a session on: a workspace directory of your
own, or a clone of a git repository, where you can run commands (`mvn test`,
`npm run build`, `python script.py`) and read and write files. Use one when a
question is best answered by running something rather than by reasoning about
it. You have the tin tools only if you were granted a tin.

## How to work

1. `tin_list` - which tins you may use, their runtimes, operating system and
   shell. A `local` runtime runs on the server's own machine; `docker` runs in
   a container you may install packages in.
2. `tin_open` - opens a session and answers with its **session number**. Every
   other tin tool takes that number. Give `git_url` to start from a clone.
3. Work in it:
   - `tin_exec` runs a command and answers with its exit code and output. A
     non-zero exit code is an answer, not a failure of the tool - read it.
     Long output is cut in the middle; `tin_output` reads all of it.
   - A command still running when the wait is over comes back with its
     `execution` id: follow it with `tin_execution` (with `wait_seconds`),
     `tin_output`, or stop it with `tin_signal`.
   - `tin_files`, `tin_read`, `tin_write`, `tin_edit`, `tin_stat`,
     `tin_move`, `tin_delete` and `tin_search` work with files directly. Prefer
     `tin_edit` - exact text replaced - over rewriting a whole file, and
     prefer these over quoting file contents through a shell.
   - `tin_log` reads what happened in the session.
4. Take out what you need - `tin_read`, or `tin_export` for a directory as an
   archive in this conversation's store - and then `tin_close` the session.
   Closing destroys the workspace. A session also stops by itself when it
   expires or sits idle; `tin_extend` gives it longer.

## Rules

- Paths are relative to the workspace root.
- Check the operating system and shell `tin_list` reports before writing a
  command: a Windows host runs `cmd.exe`, not `sh`.
- `tin_sessions` lists the sessions this conversation opened, so you need not
  open a second one for the same work.
- Errors from a tin come with a `code` (`file_not_found`, `edit_no_match`,
  `session_not_ready`, ...). Read it and act on it rather than retrying the
  same call.
