---
name: Tin New Project
description: Start a new project in a tin - generate it with the ecosystem's own tool, make it build and test from the first commit, and hand it over.
---

# Starting a new project in a tin

## Objective

Somebody wants something built from nothing: a service, a script, a library,
a demo. Produce a small project that builds, has at least one passing test,
and says in its README how to build, test and run it.

## How to work

1. `tin_open` without a `git_url`, with `caches` for the toolchain you will
   use. Check with `tin_list` what the tin's image has (JDK, Node, Python,
   compilers) and prefer what is already installed.
2. **Generate, don't hand-write, the skeleton.** Use the ecosystem's own
   generator - Spring Initializr (`curl https://start.spring.io/...`),
   `mvn archetype:generate`, `gradle init`, `npm create vite@latest`,
   `npm init -y`, `cargo new`, `go mod init`, `dotnet new`. Pin versions the
   generator offers rather than inventing them.
3. Include the build wrapper (`mvnw`, `gradlew`) so the project builds without
   a global install, and a `.gitignore` for build output.
4. Build and run the generated project once before adding anything, so a
   broken toolchain shows up before your code does.
5. Add the requested behaviour in small steps, each followed by a build and
   the tests. Write at least one test of the real behaviour, not only "context
   loads".
6. Write a short `README.md`: what it is, and the exact commands to build,
   test and run it.
7. `git init`, commit in sensible steps, and push only when you were given a
   remote and asked to.

## Hand it over

Export the project (`tin_export`) if it is not pushed anywhere, report what it
does, the commands and their results, and anything left undone - then
`tin_close`. Closing destroys the workspace.
