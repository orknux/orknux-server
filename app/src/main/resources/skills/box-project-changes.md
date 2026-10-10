---
name: Box Project Changes
description: Change an existing project in a box - read how it is built and what it expects, make a focused change, compile it, run its tests, and report honestly.
---

# Changing an existing project in a box

## Objective

Somebody asked for a change to a project that already exists: a fix, a
feature, a dependency bump. Make that change the way the project's own
maintainers would, and prove it builds and its tests pass, in a box session.
Load the Boxes skill first if you have not used the box tools before.

## 1. Get the code

- `orkbox_open` with `git_url` (and `git_ref` for a branch or tag). Pass
  `caches` for the build tool you expect (`maven`, `gradle`, `npm`, ...) so
  dependencies are not downloaded from scratch.
- Never put a password or token in `git_url`. If the clone needs credentials,
  ask for them to be given to you another way, or say you could not clone.

## 2. Read before you touch anything

Read, in this order, whatever of these exists at the root and in the
directory you will change:

1. `AGENTS.md`, `CLAUDE.md`, `.github/copilot-instructions.md` - rules written
   for an agent. They win over your habits.
2. `README.md` - what the project is, how to build and run it.
3. `CONTRIBUTING.md`, `docs/` - conventions, commit style, how tests are run.
4. The build file: `pom.xml`/`mvnw`, `build.gradle(.kts)`/`gradlew`,
   `package.json` (its `scripts`), `Makefile`, `pyproject.toml`, `Cargo.toml`,
   `go.mod`, `*.sln`/`*.csproj`. Prefer the wrapper the repository ships
   (`./mvnw`, `./gradlew`) over a globally installed tool.
5. CI configuration (`.github/workflows/`, `.gitlab-ci.yml`) - the exact
   commands the project trusts to build and test itself.

Use `orkbox_search` and `orkbox_files` to find the code involved instead of reading
the whole tree. Read the surrounding code and its tests before editing.

## 3. Establish a baseline

Build and run the tests **before** changing anything. A failure that was
already there is not yours, and you need to know which it is. Note the
command, how long it took and what failed. If the build cannot run at all
(missing toolchain, no network, a service it needs), say so and stop rather
than changing code you cannot check.

## 4. Make the change

- Work on a branch: `git checkout -b <short-descriptive-name>`.
- Keep it focused: only what the request needs. No drive-by reformatting,
  renames or upgrades.
- Match the code around it - naming, comment density, error handling, test
  style. Use `orkbox_edit` for exact replacements; rewrite a whole file only when
  it is new or tiny.
- Add or update a test that fails without your change and passes with it,
  where the project has tests for that kind of code.

## 5. Prove it

1. Compile first; fix every error you introduced.
2. Run the tests closest to your change, then the full suite the project's CI
   runs. Long runs: start them with `wait: false` and follow with
   `orkbox_execution` and `wait_seconds`.
3. Run the formatter or linter only if the project uses one in CI, and only on
   files you touched.
4. Read `git diff` end to end. Remove debug output and leftover experiments.

## 6. Hand it over

- Commit with a message in the project's style (look at `git log`).
- Push or open a pull request only when you were asked to and have the means.
- Report: what you changed and why, the commands you ran and their results
  (tests passed / failed, with counts), anything you could not verify, and
  failures that were already there before you started. Never claim a build or
  test passed that you did not see pass.
- If the session holds work somebody needs, export it (`orkbox_export`) before
  `orkbox_close`; closing destroys the workspace.
