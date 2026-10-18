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

**The `.deb` names its dependencies.** electron-builder's defaults left out
ALSA, and a clean Debian installed the package and then would not start it.
Smoke-test a package on a clean image, not on the build image, which has
everything.

**No Node on the host.** Build and test in `docker/Dockerfile`. The Windows
`.exe` can be made here in `electronuserland/builder:wine` (see README) and
`win-unpacked` smoke-tested on this machine; the MSI needs real Windows, which
is CI's.
