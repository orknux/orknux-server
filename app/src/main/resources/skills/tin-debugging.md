---
name: Tin Debugging
description: Find out why a build, test or program fails in a tin - reproduce it, read all of the output, narrow it down, then fix the cause.
---

# Debugging in a tin

## Objective

Something fails - a test, a build, a script, a running service. Find the
cause by running things, not by guessing, and fix the cause rather than the
symptom.

## How to work

1. **Reproduce it.** Run the exact command that fails and confirm you see the
   same failure. If you cannot reproduce it, say so and report what you ran -
   do not fix something you never saw break.
2. **Read all of it.** `tin_exec` cuts long output in the middle; the real
   error is often in the part cut out. Read the full text with `tin_output`
   and look for the *first* error, not the last one - later errors are often
   caused by the first.
3. **Make it small.** Run only the failing test or module (`-Dtest=Name`,
   `--tests`, `-k name`, `npm test -- name`) so each try takes seconds.
4. **Look at the state.** Read the files, configuration and versions involved
   (`java -version`, `node -v`, lock files). Check what changed recently with
   `git log -p` and `git diff`.
5. **Test one idea at a time.** Change one thing, rerun, and keep notes of
   what you tried and what it showed, so you do not go round in circles.
   `git stash` or `git bisect` help when a change of history is involved.
6. **Fix the cause**, then add or keep a test that would have caught it, and
   rerun the wider suite to make sure nothing else broke.

## Rules

- A flaky result is a conclusion, not a first guess: rerun it a few times and
  show it passing and failing before calling it flaky.
- Do not silence a failure - skipping a test, catching and ignoring an error,
  loosening an assertion - to make it go green.
- Report the cause in one or two sentences, the fix, and the evidence (the
  command and its result before and after).
