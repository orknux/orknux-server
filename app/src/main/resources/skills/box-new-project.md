---
name: Box New Project
description: Load when somebody wants a new project built from nothing in a box - generate it with the ecosystem's own tool, make it build and test from the first commit, and hand it over.
grant: box
---

# Starting a new project in a box

## Checklist

1. `orkbox_open` without `git_url`, with `caches` for the toolchain.
2. `orkbox_list` and `java -version`, `node -v`, `python --version`: use what
   the box already has.
3. Generate the skeleton with the ecosystem's tool. Do not hand-write it.
4. Build it and run its tests once, before adding anything.
5. Add the requested behaviour in small steps; build and test after each.
6. Write at least one test of the real behaviour.
7. Write a short `README.md`: what it is, and the commands to build, test, run.
8. `git init`, commit. Push only to a remote you were given, and only if asked.
9. Hand it over (below), then `orkbox_close`.

## Generators

- Java: `curl https://start.spring.io/starter.zip -d dependencies=web -o app.zip`,
  `mvn archetype:generate`, `gradle init`
- JavaScript: `npm create vite@latest`, `npm init -y`
- Others: `cargo new`, `go mod init`, `dotnet new`, `uv init`

Keep the versions the generator picks. Keep its build wrapper (`mvnw`,
`gradlew`) and add a `.gitignore` for build output.

## Hand it over

- Not pushed anywhere? `orkbox_export` the project first - `orkbox_close`
  destroys the workspace.
- Report what it does, the build and test commands and their results, and
  what is left undone.
