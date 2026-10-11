---
name: Boxes
description: Load before you first use the orkbox_ tools - opening a sandbox session, cloning a repository to search or change it, running commands, reading and editing files.
grant: box
---

# Boxes

## Checklist

1. `orkbox_list` - which boxes you may use, and their operating system and
   shell.
2. `orkbox_open` - with `git_url` to start from a clone. Keep the **session
   number** it answers with; every other box tool takes it.
3. Send the person the session link, once (see below).
4. Changing a repository? Load **Box Project Changes** now, before you edit
   anything.
5. Work: `orkbox_exec`, `orkbox_search`, `orkbox_read`, `orkbox_edit`.
6. Take out what you need, then `orkbox_close`.

A box is a sandbox: a workspace directory of your own, or a clone of a git
repository, where you run commands (`./mvnw test`, `npm run build`,
`python script.py`) and read and write files. Use one when running something
answers the question better than reasoning about it.

## Clone rather than browse

When a question needs more than a handful of a repository's files - finding
where something is defined, how it is used, what a bug touches - open a box
session with `git_url` and work on the clone: `orkbox_search` across the whole
tree, `orkbox_read` what it finds, `git log` and `git grep` with `orkbox_exec`.
Fetching file after file, or search after search, through a code-hosting
service's tools is slow, rate-limited and sees one file at a time; a clone
answers every later question at once. Use those tools for what only the
service knows - issues, pull requests, reviews - and for a single file you
already know the path of.

## Send the person the link

`orkbox_open` answers with a `url`: this conversation's page with the session
open, where the person can watch your commands run. In your next message to
them, once per session, write one sentence in their language saying you
started a box session and what you will do in it, with the words for the
session as the link:

- "I've started a [box session](url) where I'll implement the requested
  changes."
- In Polish the session is a "sesja w skrzynce": "Rozpocząłem [sesję w
  skrzynce](url), będę tam implementował wymagane zmiany projektowe."

Write the link the way the place you answer in shows one: in Slack
`<url|sesję w skrzynce>` or `<url|box session>`; elsewhere a markdown link.
This matters most in Slack, where they cannot see your tool calls.

## The tools

- `orkbox_exec` runs a command and answers with its exit code and output. A
  non-zero exit code is an answer - read it. Long output is cut in the middle;
  `orkbox_output` reads all of it.
- A command still running when the wait ends comes back with an `execution`
  id: follow it with `orkbox_execution` (`wait_seconds`), read it with
  `orkbox_output`, stop it with `orkbox_signal`.
- Files: `orkbox_files`, `orkbox_read` (`start_line`/`end_line` for part of a
  file), `orkbox_search`, `orkbox_write`, `orkbox_edit`, `orkbox_stat`,
  `orkbox_move`, `orkbox_delete`. Use these rather than `cat` or `echo` through
  a shell.
- `orkbox_edit` changes part of a file: exact text (`old_text` and
  `new_text`), lines by number (`start_line`, `end_line`, with `old_text` as an
  optional guard), or a regular expression (`regex: true`, `$1` in
  `new_text`). Prefer it to rewriting a whole file.
- `orkbox_undo` takes back the last edits and writes; `orkbox_redo` puts them
  back. Only the last few steps, kept in memory; a file a command changed since
  is refused.
- `orkbox_log` reads what happened in the session; `orkbox_sessions` lists this
  conversation's sessions, so you need not open a second one for the same work.
- `orkbox_export` packs a directory into this conversation's store.
  `orkbox_close` destroys the workspace - export first if anybody needs it. A
  session also stops when it expires or goes its idle timeout with no command;
  `orkbox_extend` gives it longer. Waiting for the person counts as idle - before
  you hand over for something that may take a while, such as trying a port you
  exposed, raise `idle_timeout_seconds` so the session is still there after.

## Load the skill for the work

- **Box Project Changes** - changing an existing repository: branch, build,
  test, commit, pull request.
- **Box Git** - commits, pushing, pull requests, conflicts.
- **Box Debugging** - finding out why something fails.
- **Box Long Commands** - builds, suites and servers that take a while.
- **Box New Project** - building something from nothing.

## Rules

- Paths are relative to the workspace root.
- Check the operating system and shell `orkbox_list` reports before writing a
  command: a Windows box runs `cmd.exe`, not `sh`.
- Errors come with a `code` (`file_not_found`, `edit_no_match`,
  `session_not_ready`, ...). Act on it; do not repeat the same call.
- `session_not_found` means the session is gone: open a new one, and redo the
  work - nothing from the old one is left.
