---
name: Box Debugging
description: Load when a build, test or program fails in a box and you need to find out why - reproduce it, read all of the output, narrow it down, fix the cause.
grant: box
---

# Debugging in a box

## Checklist

1. Run the exact command that fails. See the same failure yourself.
2. Read **all** the output with `orkbox_output`. Find the **first** error.
3. Run only the failing test, so each try takes seconds.
4. Change one thing. Run again. Note what it showed.
5. Fix the cause, not the symptom.
6. Run the failing test, then the wider suite.
7. Report the cause, the fix, and the commands with their results before and
   after.

## How

- **Cannot reproduce it?** Say so and report what you ran. Do not fix
  something you never saw break.
- **Read all of it.** `orkbox_exec` cuts long output in the middle, and the real
  error is often in the cut part. Later errors are often caused by the first.
- **One test at a time:** `./mvnw test -Dtest=Name`, `./gradlew test --tests
  Name`, `pytest -k name`, `npm test -- name`.
- **Look at the state:** the files and configuration involved, versions
  (`java -version`, `node -v`), lock files, and what changed recently
  (`git log -p -5`, `git diff`).
- **Keep notes** of each idea and what it showed, so you do not go round in
  circles. `git stash` sets your change aside; `git bisect` finds the commit
  that broke it.
- A wrong edit: `orkbox_undo` takes it back.

## Rules

- "Flaky" is a conclusion, not a first guess: run it several times and show it
  both passing and failing before you call it that.
- Never make it green by skipping a test, catching and ignoring an error, or
  loosening an assertion.
- Never say it is fixed unless you saw the test pass after the change.
