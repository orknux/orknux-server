---
name: Box Git
description: Work with git in a box - clone, branch, commit, keep history clean, resolve conflicts, and push only when asked.
---

# Git in a box

## Objective

Use git in a box session the way a careful colleague would: changes on a
branch, small commits with clear messages, nothing destructive, nothing
pushed without being asked.

## Getting the code

- `orkbox_open` with `git_url`, `git_ref` (branch, tag or commit) and
  `git_depth` for a shallow clone of a big repository. Deepen later with
  `git fetch --deepen=<n>` if history is needed.
- Credentials never go in the URL or in a commit. If cloning or pushing needs
  them and none were provided, stop and say what is needed.

## While working

- First thing: `git status` and `git log --oneline -5` to know where you are.
- Create a branch for the work: `git checkout -b <short-name>`.
- Set an identity if commits need one and none is configured:
  `git config user.name` / `user.email` - use what you were told, or a clear
  agent identity, never a real person's.
- Commit in small steps that each build. Write messages in the repository's
  style (read `git log`): an imperative subject line, a body saying why.
- Review before every commit: `git diff --staged`. Stage paths explicitly,
  not `git add -A`, so stray files (build output, logs, secrets) stay out.

## Updating and conflicts

- `git fetch` then `git rebase origin/<base>` (or merge, if that is the
  project's habit). Resolve conflicts by reading both sides; rerun the build
  and tests after resolving.
- Never `git push --force` to a shared branch, never rewrite history somebody
  else has, never delete branches you did not create.

## Pushing and pull requests

- Push only when the request says so: `git push -u origin <branch>`.
- Open a pull request only when asked and when a tool for it is available
  (for example `gh pr create`); describe what changed, why, and how it was
  tested.
- Report the branch name, the commits, and the push or PR result.
