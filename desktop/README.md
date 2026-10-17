# Orknux desktop

Orknux on one machine, for Windows and Linux: the same server jar `orknux-one`
runs, on SQLite, started by an Electron app and shown in a window of its own.
Nothing to install beside it - the package carries its own Java.

## How it runs

The main process (`src/main.js`) is what `docker/one/entrypoint.sh` and
`docker/orknux-run.sh` are in the image:

1. **Prepares the data directory** (`src/data.js`): the database, attachments,
   the encryption key - generated once, never rewritten, and a warning when a
   database is found without one - exactly as the entrypoint does, so a
   directory moves between this app and an `orknux-one` volume unchanged.
2. **Starts the server** (`src/server.js`) on 8080, or on a free port where
   8080 is taken, with the environment `Dockerfile.one` sets.
3. **Supervises it** like `orknux-run.sh`: asks `ReleaseLauncher` which jar to
   run, runs it, starts again on exit code 75, and falls back from a release
   that failed. That is what lets the server's own signed-jar updates work here.
4. **Shows it**: a splash while the server starts, then the interface. Links to
   anywhere else open in the default browser. The window remembers its size
   and position.
5. **Stays in the tray** when the window is closed, because a scheduled
   workflow or a Slack mention does not wait for the window. Quit from the tray
   icon. Where the desktop has no tray, closing the window quits.
6. **Stops the server first** on quit and waits for it, so the database is
   closed by the process that opened it.

If the server stops on its own, a dialog shows its last log lines and offers to
start again.

### Who signs in

By default nobody: the server listens on `127.0.0.1` only and runs with
`ORKNUX_AUTH_METHOD=NONE`, so the only person who can reach it is the one at
this machine. `desktop.json` in the data directory can say
`{ "network": "exposed" }` instead: the server then listens on every address,
everybody signs in with an account this installation holds, and the first such
start generates an administrator password and shows it once. Phase 2 puts that
switch in the interface; until then the file is edited by hand.

### Where the data lives

| | |
|---|---|
| Windows | `%APPDATA%\Orknux\data` |
| Linux | `~/.config/Orknux/data` |

`orknux.db`, `secret.key`, `attachments/`, `releases/` (jars the server
installed), `tmp/`, `logs/orknux.log` (rotated at 10 MB, five kept) and
`desktop.json`. **Back `orknux.db` and `secret.key` up together** - a database
without its key opens with every stored credential unreadable. Set
`ORKNUX_DATA` to put it somewhere else.

## Building

There is no Node on the development machine; everything runs in
`docker/Dockerfile` (Node, what Electron needs, a virtual display, librsvg).

```
docker build -t orknux-desktop-dev desktop/docker
docker run --rm -v "$PWD/desktop:/desktop" orknux-desktop-dev npm ci
```

A package needs two things the repository does not hold, both per platform:

```
scripts/server.sh                  # the jar the reactor built (./mvnw package -Pwith-ui first)
scripts/server.sh --image 1.1.1    # or the one inside orknux/orknux-one:1.1.1
scripts/runtime.sh <JDK 25 home>   # a jlinked runtime in resources/jre (runtime.ps1 on Windows)
```

The runtime holds the modules the `orknux-one` image's JRE does
(`scripts/modules.txt`), Graal's compiler included, stripped: about 80 MB
instead of 190.

Then:

```
npm run dist:linux        # dist/Orknux-<version>.AppImage and orknux-desktop_<version>_amd64.deb
npm run dist:win          # dist/Orknux-Setup-<version>.exe and Orknux-<version>.msi - on Windows only
```

`dist:linux:store` and `dist:win:store` build the same packages with
self-update switched off (`orknux.selfUpdate` in `package.json`, baked in at
build time). Use them for anything a store or a distribution updates: those
replace the app themselves, and refuse one that replaces its own code
underneath them. `ORKNUX_SELF_UPDATE=false` switches it off on a single machine,
as it does in the images.

### The smoke test

`ORKNUX_DESKTOP_SMOKE=<dir>` makes the app load the interface, write
`window.png` and `result.json` there, and quit:

```
docker run --rm --shm-size=1g -v "$PWD/desktop:/desktop" \
  -e ORKNUX_DESKTOP_SMOKE=/desktop/.smoke/dev -e ORKNUX_DATA=/tmp/data \
  orknux-desktop-dev xvfb-run -a node_modules/.bin/electron . --no-sandbox --disable-gpu
```

The same works for the AppImage (`APPIMAGE_EXTRACT_AND_RUN=1`) and for the
`.deb` installed on a clean `debian:bookworm`.

## Phase 2

Done: under `ORKNUX_DESKTOP=true` a loopback server with sign-in off shows no
open-access warning and names its one account after the person at the machine;
the server stops when this app closes its standard input, which is how a quit
asks it on Windows too; and the release launcher skips a release built for a
newer Java than the bundled runtime, leaving it for a later app to run.

Not done yet, in roughly the order it matters:

- **A switch in the interface for network access**, writing `desktop.json`, with
  the restart it needs. The server has to know it is a desktop
  (`ORKNUX_DESKTOP=true`) to offer it.
- **`ORKNUX_ADDRESS`** for the bind address, instead of passing Spring's
  `--server.address`, to match every other setting.
- **CI**: build the jar once, then a Windows and a Linux job each run
  `runtime.*`, `npm ci` and `dist:*`, and attach the packages to the GitHub
  release. Windows code signing, or SmartScreen warns on every install.
- **Window title and native menus** (Edit for copy/paste shortcuts on Linux).
