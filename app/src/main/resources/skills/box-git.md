---
name: Box Git
description: Load before you commit, push or open a pull request from a box. Branch first, build before committing, push your branch only, never main or master, never force.
grant: box
---

# Git in a box

## Checklist

1. `git status` and `git branch --show-current` - where am I?
2. Find the default branch: `git symbolic-ref --short refs/remotes/origin/HEAD`
   (it says `origin/main` or `origin/master`).
3. On the default branch? Make your own first: `git checkout -b fix/<short-name>`.
4. Build and run the tests. **Do not commit code that was not built.**
5. `git diff` - read every line you are about to commit.
6. `git add <path> <path>` - name the files. Not `git add -A`, not `git add .`.
7. `git commit -F <message-file>` (see below).
8. Push only if the person asked: `git push -u origin <your-branch>`.
9. Open a pull request, or give the branch and the compare link.
10. Report the branch, the commit hash, the push result and the PR link.

## The rules

- **Never push to `main` or `master`** (or whatever step 2 printed). Not even
  when asked to "commit" - a commit goes on your branch, and the pull request is
  how it reaches the default branch.
- **Never `git push --force`**, never rewrite history somebody else has, never
  delete a branch you did not create.
- Never put a token or password in a URL, a file or a commit.
- If a push or a clone needs credentials you do not have, stop and say so. Do
  not hand the work to another agent or a cloud coding service to get around
  it.

## Getting the code

- `orkbox_open` with `git_url`; `git_ref` for a branch or tag; `git_depth: 1`
  for a big repository (`git fetch --deepen=50` later if you need history).
- `git log --oneline -10` shows the commit message style to copy.

## Committing

Quoting a message inside `orkbox_exec` breaks easily (a Windows box runs
`cmd.exe`). Write the message to a file instead and commit from it:

1. `orkbox_write` path `.git/COMMIT_DRAFT`, content: a short imperative subject
   line, a blank line, then why the change was made.
2. `orkbox_exec`: `git commit -F .git/COMMIT_DRAFT`

No identity configured? Set one for this repository only:
`git config user.name "Orknux agent"` and `git config user.email
"agent@orknux.invalid"` - or what you were told. Never a real person's.

## Pull request

- Look in your tool list for one that creates a pull request or merge
  request, and ask `tool_find` for "pull request" if you have it. Pass the base branch (step 2), your
  branch, a title, and a body saying what changed, why, and how it was tested
  (the commands and their results).
- No such tool? Give the person the branch name and the compare link, for
  GitHub `https://github.com/<owner>/<repo>/compare/<default>...<your-branch>`.
- A pull request needs a pushed branch: push first (step 8).

## Updating and conflicts

- `git fetch origin` then `git rebase origin/<default>`. Resolve each conflict
  by reading both sides, then build and test again before you continue.
- Lost? `git status` says what git expects next. `git rebase --abort` goes back.
