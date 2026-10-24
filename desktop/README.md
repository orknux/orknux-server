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
4. **Shows it**: a splash while the server starts, then the interface, in tabs
   (`src/tabs.js`): a link the page opens in a new tab, Ctrl+click and the
   middle button open one, Ctrl+T, Ctrl+W, Ctrl+Tab and Ctrl+Shift+Tab work as
   in a browser, and closing the last tab closes the window. Next and previous
   tab are also Ctrl+PageDown and Ctrl+PageUp, or whatever Preferences says
   (`src/page-preload.js` is how the interface tells the app). Right-click on a
   link into Orknux, or Shift+click, opens it in a new window. Web, mail and
   phone links to anywhere else go to the system's browser or mail program;
   any other kind is refused and said so (`src/links.js`). The window remembers
   its size and position.
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
this machine. Admin › Settings › **Network access** switches it: "Anyone on the network" asks
for the password the person at the machine will sign in with, under an account
named after their user on this computer, and writes `desktop.json` in the data
directory (`{ "network": "exposed", "account": "<name>" }`). The server then
leaves with exit code 75, the start loop reads `desktop.json` again, and the
next start listens on every address with everybody signing in; the page waits
and reloads. "This computer only" goes back the same way. Edited by hand, a
`desktop.json` without an `account` still works as before: the first exposed
start generates an administrator password and shows it once.

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
scripts/aot.sh                     # optional: the jar unpacked with an AOT cache, for a start twice as quick
```

`scripts/aot.sh` comes last, because the cache it makes is only good for the
runtime that made it and the files it was made from; it takes a few minutes,
and on Windows it runs from Git Bash. A package without it starts the jar as
it is, which works and is slower.

The runtime holds the modules the `orknux-one` image's JRE does
(`scripts/modules.txt`), Graal's compiler included, stripped: about 80 MB
instead of 190.

Then:

```
npm run dist:linux        # dist/Orknux-<version>.AppImage and orknux-desktop_<version>_amd64.deb
npm run dist:win          # dist/Orknux-Setup-<version>.exe and Orknux-<version>.msi - on Windows only
```

On a Windows machine without Node, the `.exe` installer can still be made in
electron-builder's own image, which runs NSIS under wine; the MSI cannot, since
WiX does not run there. The runtime has to be the Windows one - `runtime.ps1`
on the host - and `node_modules` the one the Linux image installed:

```
.\scripts\runtime.ps1
docker run --rm -v "${PWD}:/project" -w /project electronuserland/builder:wine `
  node_modules/.bin/electron-builder --win nsis
```

`dist/win-unpacked/Orknux.exe` runs as it is, and takes the same smoke test:
`ORKNUX_DESKTOP_SMOKE=<dir>` and `ORKNUX_DATA=<dir>` in its environment.

### In CI

A `v*` tag builds both platforms after the images are published - the browser
product ships first - from the jar `release-jar` signed, and attaches the
`.exe`, `.msi`, `.deb` and `.AppImage` to the GitHub release. The Windows
installers are signed when `ORKNUX_WINDOWS_CERTIFICATE` (a base64 `.pfx`) and
`ORKNUX_WINDOWS_CERTIFICATE_PASSWORD` are set as repository secrets; without
them they are built unsigned, with a warning, and SmartScreen warns on install.

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

- **`ORKNUX_ADDRESS`** for the bind address, instead of passing Spring's
  `--server.address`, to match every other setting.
- **A Windows code-signing certificate** in the two secrets above, or
  SmartScreen warns on every install.
- **Window title and native menus** (Edit for copy/paste shortcuts on Linux).
