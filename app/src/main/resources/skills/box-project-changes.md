---
name: Box Project Changes
description: Load before you change, commit or push anything in an existing repository in a box - a fix, a feature, "fix it, commit and open a PR". Branch, build, test, then push a branch and open a pull request; never main.
grant: box
---

# Changing an existing project in a box

## Checklist - do these in order, and do not skip one

1. Open a session on the repository: `orkbox_open` with `git_url`.
2. Read the rules: `AGENTS.md`, `CLAUDE.md`, `README.md`, `CONTRIBUTING.md` -
   whichever exist.
3. Find the build tool and the test command (table below).
4. Make a branch: `git checkout -b fix/<short-name>`. Never work on `main` or
   `master`.
5. Build and test **before** you change anything. Write down what already fails.
6. Make the change with `orkbox_edit`. Keep it small.
7. Build and test again. Read the result. Fix what you broke.
8. Read `git diff`. Commit on your branch.
9. Push **your branch** only if the person asked you to push or open a PR.
10. Open the pull request, or give the person the branch and the compare link.
11. Report: what you changed, the build and test commands and their results,
    what you did not check.

If a step fails and you cannot fix it, stop and say so. Do not skip ahead to
committing.

## Find the build tool

Look at the files at the repository root (`orkbox_files`). Use the wrapper the
repository ships, not a globally installed tool.

| File at the root          | Build                         | Tests                              |
|---------------------------|-------------------------------|------------------------------------|
| `mvnw`, `pom.xml`         | `./mvnw -q compile`           | `./mvnw test -Dtest=ClassName`     |
| `gradlew`, `build.gradle` | `./gradlew build -x test`     | `./gradlew test --tests ClassName` |
| `package.json`            | `npm ci` then `npm run build` | `npm test` (read its `scripts`)    |
| `pyproject.toml`          | `pip install -e .`            | `pytest path/to/test_file.py`      |
| `Cargo.toml`              | `cargo build`                 | `cargo test name`                  |
| `go.mod`                  | `go build ./...`              | `go test ./...`                    |
| `Makefile`                | `make`                        | `make test`                        |

Also read `.github/workflows/*.yml`: the commands CI runs are the ones the
project trusts. On a Windows box (`orkbox_list` says so) use `mvnw.cmd` and
`gradlew.bat`. A build that takes minutes: `orkbox_exec` with `wait: false`,
then `orkbox_execution` with `wait_seconds` - load the Box Long Commands skill.

If the build cannot run at all (no JDK, no network, a database it needs), say
so and stop. Do not change code you cannot build.

## Editing files

- `orkbox_search` finds the code; `orkbox_read` with `start_line` and
  `end_line` reads only the part you need.
- `orkbox_edit` with `old_text` and `new_text` replaces exact text. Copy
  `old_text` from what `orkbox_read` showed, spaces included.
- `orkbox_edit` with `start_line` and `end_line` replaces those lines. Pass
  `old_text` too as a guard: the edit is refused if the lines no longer hold
  it. Several line edits in one call: go from the bottom of the file up, since
  each edit moves the lines below it.
- `orkbox_edit` with `regex: true` treats `old_text` as a regular expression;
  `new_text` may use `$1`, `$2` for its groups. Add `replace_all: true` to
  change every match.
- A wrong edit: `orkbox_undo` takes back the last `orkbox_edit` or
  `orkbox_write` (`steps` for more than one); `orkbox_redo` puts it back. Only
  the last few steps are kept, in memory, and undo refuses a file that a
  command changed since. Deletes and moves cannot be undone.
- Change only what the request needs. No reformatting, no renames, no upgrades
  on the side. Add or update a test that fails without your change.

## Prove it

- Run the build and the tests closest to your change, then what CI runs.
- Read the output. `orkbox_output` gives the part `orkbox_exec` cut out. Look
  for `BUILD SUCCESS`, `Tests run: N, Failures: 0`, exit code 0.
- **Never commit code that was not built.** If the tests fail because of your
  change, fix it or tell the person; do not commit it as done.

## Commit, push, pull request

Load the Box Git skill for the commands. In short:

- Commit on your branch, never on `main`/`master`.
- `git push -u origin <your-branch>` - only when asked. Never push to `main` or
  `master`, never `--force`.
- Open the pull request with a tool you hold that creates pull requests or
  merge requests - look in your tool list, and ask `tool_find` for "pull
  request" if you have it. None? Give the person the branch name and the
  compare link.
- Push refused for lack of credentials? Say so and give the person the branch
  or an export (`orkbox_export`). Do not hand the work to another agent or a
  cloud coding service to get around it.

## Report

Say, plainly:

- what you changed, in which files, and why;
- the exact build and test commands, and their result (passed / failed, with
  counts) - copy the line from the output;
- the branch, the commit, the pull request link;
- what you did not verify, and what was already failing before you started.

Never say a build or test passed that you did not see pass.
