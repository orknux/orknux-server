# orknux-one

The whole of Orknux in one container: the interface, the server, and a SQLite
file for a database. One command, nothing to configure, nothing else to run.

```
docker run -d --name orknux -p 8080:8080 -v orknux-data:/var/lib/orknux orknux/orknux-one
docker logs orknux
```

Open **http://localhost:8080**. The log prints a username and a password that
were generated for your installation on the first start.

**This is for trying Orknux, developing against it, and demonstrating it. It is
not a deployment**, and the reasons are listed under *What this cannot do* below
rather than left to be discovered. A real installation is
[`orknux/orknux-server`](https://hub.docker.com/r/orknux/orknux-server) — the
same jar, interface included — with a Postgres and a Temporal beside it, which is
[`deploy/compose.yaml`](https://github.com/michjak-szymanski/orknux-server/blob/main/deploy/compose.yaml).

- **Source:** https://github.com/michjak-szymanski/orknux-server
- **Licence:** AGPL-3.0-or-later
- **Exposes:** `8080` — the server, which serves the interface as well as
  `/api`, `/graphql` and `/mcp`
- **Runs as:** `orknux`, not root
- **Data:** `/var/lib/orknux`
- **Tags:** `latest` follows `main`; `X.Y.Z` and `X.Y` come from release tags;
  `sha-<commit>` never moves.

## The first start

Three things exist that nobody supplied, and each is created once and then left
alone:

| File in `/var/lib/orknux` | What it is |
| --- | --- |
| `orknux.db` | The database. Everything this installation knows. |
| `secret.key` | 32 random bytes. Every credential in the database is encrypted with it. |
| `admin-password` | The password printed on the first start, mode `600`. |

The password is generated rather than documented. A default written in a README
is a password every copy of this image shares and most of them keep, which on
anything reachable from another machine is the same as no password at all. It is
24 characters, it is printed in the log, and it is kept in the file above so that
scrolling past it is not a reinstall:

```
docker exec orknux cat /var/lib/orknux/admin-password
```

**Change it from inside — Account → Password — and then delete that file.** The
server only ever *creates* this account: it will not put a password back that
somebody changed, and it will not restore a role somebody took away. Once the
file is gone nothing is seeded on the next start.

Set `ORKNUX_BOOTSTRAP_ADMIN_USERNAME` and `ORKNUX_BOOTSTRAP_ADMIN_PASSWORD` to
choose them yourself instead. The password has to be at least 12 characters, the
same minimum every other password in the product holds to.

## The volume, and the key inside it

**`-v orknux-data:/var/lib/orknux`, or a restart is a new installation.**
Without it Docker still makes an anonymous volume — so `docker restart` keeps
your data — but `docker rm` orphans it and there is no name to bring it back
with.

`secret.key` is the one file worth understanding. It encrypts every credential
the server is trusted with, so that a copy of the database on its own is not
enough to use them. It is generated on the first start **because there is
nowhere else for it to come from**, and it is written next to the database
because the two are only useful together:

- **Back them up together.** The database without the key has unreadable
  credentials in it; the key without the database is 44 characters of nothing.
- **Copying the database somewhere without the key strands every secret in it.**
  They are not corrupted and they are not recoverable — they have to be typed in
  again, one at a time. The container says so, loudly, when it finds a database
  and no key.
- Set `ORKNUX_SECRET_KEY` to supply your own, and nothing is written to disk.
  Admin → Doctor is the screen that says whether the key is set, the right
  length, and whether every stored secret can still be read with it.

## What this cannot do

Everything here is a consequence of *one container*, and none of it is a bug.

**Runs are not durable.** There is no Temporal, so a workflow runs on the thread
that started it. Nothing is retried, nothing resumes, and a restart in the middle
of a run leaves that execution at RUNNING for ever — there is no engine left to
finish it or fail it. In a real installation a step that fails is retried
according to the workflow's settings.

**The marketplace needs a way out.** Plugins install from a catalog the server
fetches over the network; an installation with no way out has the Plugins screen
and the upload, and an empty shelf. Point `ORKNUX_MARKETPLACE_URL` at your own
mirror, or leave it empty and load plugins by hand.

**A workflow that waits longer than five minutes fails, by design.** The inline
engine waits on the thread carrying the run, so a wait is capped
(`ORKNUX_INLINE_MAX_WAIT`) and the step fails with a message telling you to
enable Temporal. "Wait a day" cannot work here.

**Starting a run answers with a finished one.** `startExecution` returns a
completed execution rather than a running one, because it has already finished by
the time it answers. Anything written against the API here will see a different
shape in a real deployment.

**No LDAP and no OIDC.** There is no directory and no identity provider in this
container, so nothing about directory sign-in, group mapping or SSO can be tried
in it. The internal administrator above is the whole of the way in, and the
container says so: it starts with `ORKNUX_AUTH_METHOD=INTERNAL`, so the sign-in
card offers a password box rather than single sign-on and the monitoring screen
draws no card for a directory that is not here. Point `ORKNUX_LDAP_URLS` or the
OIDC settings at something real if you need that - set `ORKNUX_AUTH_METHOD`
alongside them, which overrides the default above - and you are then running a
two-container installation with extra steps.

**`ORKNUX_AUTH_METHOD=NONE` turns authentication off**, which is worth knowing
about here because trying the product out is what this image is for. Nobody
signs in, no sign-in screen appears, and every request acts as one identity -
`everyone` - holding the built-in `Administrators` role. So anybody who can
reach the published port administers the container and can use every credential
stored in it: fine on a laptop, not fine on anything else without a gate of your
own in front. It is never the default and never a fallback - an unrecognised
value stops the container rather than opening it - and it says so in the startup
log, on the Doctor screen and across the top of every page.

**SQLite, so: one writer at a time, one process, one machine.** Writes are
serialised rather than concurrent; two containers on one file over a network
share will corrupt it. Timestamps have no time zone — a moment is stored as a
moment and read back in the server's own zone. A backup is a file copy of
`orknux.db` together with its `-wal` file, taken while nothing is writing.

**The bundled manual describes the real thing, not this.** The manual at `/docs`
is the same one the published interface ships and it says plainly that runs are
carried out by Temporal and retried. In this image they are not. That page is
correct about the product and wrong about this container, and the difference is
this section rather than a fork of the documentation.

## Settings

Every setting from
[`orknux/orknux-server`](https://hub.docker.com/r/orknux/orknux-server) works
here and is documented there; this image only changes which defaults make sense
when everything is one container.

| Variable | What it does | Default here |
| --- | --- | --- |
| `ORKNUX_SECRET_KEY` | Encrypts stored credentials. | generated into `/var/lib/orknux/secret.key` |
| `ORKNUX_BOOTSTRAP_ADMIN_USERNAME` | The first administrator. | `admin` |
| `ORKNUX_BOOTSTRAP_ADMIN_PASSWORD` | Their password, once. At least 12 characters. | generated into `/var/lib/orknux/admin-password` |
| `ORKNUX_DATA` | Where this image keeps the key and the first password. | `/var/lib/orknux` |
| `ORKNUX_DB_URL` | The database. Point it at a Postgres and this image will use one. | `jdbc:sqlite:/var/lib/orknux/orknux.db` |
| `ORKNUX_TEMPORAL_ENABLED` | `false` is what makes one container possible. Setting it `true` requires a reachable Temporal, and the server **refuses to start** without one. | `false` |
| `ORKNUX_PORT` | The port the server, and with it the interface, answers on inside the container. Leave it: the healthcheck asks 8080. | `8080` |
| `ORKNUX_ATTACHMENTS_LOCATION` | Chat attachments, in the volume. | `/var/lib/orknux/attachments` |
| `ORKNUX_BASE_URL` | Where this installation is: what a mailed password reset link points at, and the host on every picture link handed to a model. A path with no host behind it is a link that resolves nowhere once a model has copied it into a message. | `http://localhost:8080` |
| `ORKNUX_ALLOWED_ORIGINS` | Empty, because the interface is served from this origin. | empty |
| `JAVA_OPTS` | | `-XX:MaxRAMPercentage=75` |

Publishing on another port changes what `ORKNUX_BASE_URL` should be, and nothing
else: `8080` is fixed inside the container.

## Health

`HEALTHCHECK` fetches `/api/auth/method`, which needs no credentials, so a
healthy container means the server is answering — and with it the interface,
which the same process serves. The start period is two minutes, because the first
start migrates a schema before it answers anything.

## Upgrading

Pull the new tag and start it on the same volume. Flyway migrates the database on
the way up. Take a copy of `/var/lib/orknux` first, with the container stopped: a
directory copy is the whole of the backup story here, there is no `pg_dump` to
fall back on, and downgrading is restoring that copy rather than running an
older tag.

Or update in place: **Admin -> Updates** installs a release from the
marketplace, or a jar you upload, into the database, restarts on it, and can roll
back to any release it still keeps. Every jar must be signed with the Orknux
release key this image carries; one that is not, or that changed after it was
stored, is refused and the image's own jar runs. **On by default**;
`ORKNUX_SELF_UPDATE=false` turns it off. A newer image always wins over a release
chosen under an older one, so pulling a new tag still upgrades.
