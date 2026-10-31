# Working in desktop/

The Electron app that runs Orknux on one machine. See README.md for how it runs
and how it is built; this is why it is the way it is.

**It is a supervisor, not a second server.** Everything Orknux does is in the
jar. The main process starts it, shows it and stops it, and should never grow a
feature the interface could have - a setting that lives here is a setting the
`orknux-one` image does not have.

**The data directory is the image's volume.** Same file names, same first-start
rules, ported from `docker/one/entrypoint.sh` sentence by sentence. A change to
what the entrypoint invents is a change here too, or a directory stops moving
between the two.

**The environment is `Dockerfile.one`'s, the loop is `orknux-run.sh`'s.** Exit
code 75 means start again, `ReleaseLauncher` chooses the jar, a failed release
is fallen back from. Matching them is what lets the server's self-update work
here without a desktop-specific path, so diverge only with a reason written
beside it.

**Self-update is switched at build time.** `orknux.selfUpdate` in
`package.json`, overridden by `-c.extraMetadata` for store and distribution
builds. A store that ships an app which replaces its own code refuses it; a
setting a person could flip would put the store build one click from that.

**Local means loopback and no sign-in.** `NONE` auth is only ever paired with
`127.0.0.1`. Anything that could reach the server from the network must come
with `INTERNAL` and a password; `data.js` is the one place that pairs them.

**Its own temporary directory.** `-Djava.io.tmpdir` under the data directory,
because a local box keeps sessions under the temp directory and two
installations sharing the system's cleaned up each other's.

**The shipped jar starts from its unpacked copy.** `scripts/aot.sh` unpacks
`app.jar` into `resources/server/app/` and makes an AOT cache there with the
runtime the package carries; `launchOf` in `server.js` starts that copy, from
inside its directory, with a relative `-jar` - the cache records file names, and
a relative one is what survives installation. Measured on Windows it halves the
start (#650). `app.jar` stays the jar the launcher and the update screen read,
and an installed release still starts as a jar. Rebuild the cache whenever the
jar or the runtime changes; a stale one is refused by the JVM, not misused.

**The `.deb` names its dependencies.** electron-builder's defaults left out
ALSA, and a clean Debian installed the package and then would not start it.
Smoke-test a package on a clean image, not on the build image, which has
everything.

**`links.js` decides what a link is, and nothing else does.** A page of the
server stays in the app, always on `127.0.0.1` - the server's own links say
`localhost`, a different site to Chromium, with no session and its writes
refused as cross-site. The web, mail and the phone go to the operating system,
and every other scheme is refused out loud, because Windows and Linux both run
whatever is registered for a scheme and a link in a chat is something an agent
wrote. `node --test` covers it; tabs and the strip need a display. A page of
ours opens in a tab or a window of this app and never in the browser, where it
would be another site with no session.

**The tab keys are the interface's.** Next and previous tab are shortcuts on
Preferences like any other, kept in the page's storage; `page-preload.js` is
the one door they come through, and the only thing a page may say to this
process. `links.js` holds the same defaults for the moments before a page has
spoken - change one, change the other.

**A split is a tree, and a drag is the browser's own.** `split.js` holds the
panes and has no Electron in it, so `node --test` covers it. A page takes the
pointer from whatever is under it, so a drag crosses the pages only because
`drag-overlay.html` lies over them while one lasts. `scripts/split-check.js`
drives that with a real pointer under Xvfb; only Linux/X11 has run it. A pane
narrower than the interface's 900 is scaled by device emulation rather than
squeezed, because its bars and tables run into each other below that; not by
`setZoomFactor`, which Chromium applies to the whole site and so to every pane.

**No Node on the host.** Build and test in `docker/Dockerfile`. The Windows
`.exe` can be made here in `electronuserland/builder:wine` (see README) and
`win-unpacked` smoke-tested on this machine; the MSI needs real Windows, which
is CI's.
