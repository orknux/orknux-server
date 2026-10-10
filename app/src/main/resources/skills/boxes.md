---
name: Boxes
description: Run commands and work with files in a sandbox - build a project, run its tests, try a script - with the orkbox_ tools.
---

# Boxes

## Objective

A box is a sandbox you can open a session on: a workspace directory of your
own, or a clone of a git repository, where you can run commands (`mvn test`,
`npm run build`, `python script.py`) and read and write files. Use one when a
question is best answered by running something rather than by reasoning about
it. You have the box tools only if you were granted a box.

## How to work

1. `orkbox_list` - which boxes you may use, their runtimes, operating system and
   shell. A `local` runtime runs on the server's own machine; `docker` runs in
   a container you may install packages in.
2. `orkbox_open` - opens a session and answers with its **session number**. Every
   other box tool takes that number. Give `git_url` to start from a clone.
   It also answers with a `url`: this conversation's page with the session
   open, where the person can watch your commands as they run.
3. **Send the person that link** in your next message to them, once per
   session - not on every message. One sentence, in their language, saying
   you started a box session and naming in a few words what you will do in
   it, with the words for the session as the link:
   - "I've started a [box session](url) where I'll implement the requested
     changes."
   - In Polish the session is a "sesja w skrzynce": "Rozpocząłem [sesję w
     skrzynce](url), będę tam implementował wymagane zmiany projektowe."

   Send it in the same thread they are following, written the way that place
   shows a link: in Slack as `<url|sesję w skrzynce>` or `<url|box session>`,
   so it reads as a short link rather than a long address; elsewhere as a
   markdown link. This matters most when you are answering in Slack, where
   they cannot see your tool calls.
4. Work in it:
   - `orkbox_exec` runs a command and answers with its exit code and output. A
     non-zero exit code is an answer, not a failure of the tool - read it.
     Long output is cut in the middle; `orkbox_output` reads all of it.
   - A command still running when the wait is over comes back with its
     `execution` id: follow it with `orkbox_execution` (with `wait_seconds`),
     `orkbox_output`, or stop it with `orkbox_signal`.
   - `orkbox_files`, `orkbox_read`, `orkbox_write`, `orkbox_edit`, `orkbox_stat`,
     `orkbox_move`, `orkbox_delete` and `orkbox_search` work with files directly. Prefer
     `orkbox_edit` - exact text replaced - over rewriting a whole file, and
     prefer these over quoting file contents through a shell.
   - `orkbox_log` reads what happened in the session.
5. Take out what you need - `orkbox_read`, or `orkbox_export` for a directory as an
   archive in this conversation's store - and then `orkbox_close` the session.
   Closing destroys the workspace. A session also stops by itself when it
   expires or sits idle; `orkbox_extend` gives it longer.

## For a particular kind of work

Load the skill that fits before you start:

- **Box Project Changes** - changing an existing repository: read its
  AGENTS.md and README, build and test before and after, report honestly.
- **Box New Project** - building something from nothing.
- **Box Debugging** - finding out why something fails.
- **Box Git** - branches, commits, conflicts and pushing.
- **Box Long Commands** - builds, suites and servers that take a while.

## Rules

- Paths are relative to the workspace root.
- Check the operating system and shell `orkbox_list` reports before writing a
  command: a Windows host runs `cmd.exe`, not `sh`.
- `orkbox_sessions` lists the sessions this conversation opened, so you need not
  open a second one for the same work.
- Errors from a box come with a `code` (`file_not_found`, `edit_no_match`,
  `session_not_ready`, ...). Read it and act on it rather than retrying the
  same call.
