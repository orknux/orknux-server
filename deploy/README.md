# Running Orknux with Docker Compose

[`compose.yaml`](compose.yaml) in this directory is a whole Orknux, from the
images published on Docker Hub. Nothing in it is built from source and nothing
in it is a path on somebody's machine, so it is one file you copy anywhere
Docker runs.

```
curl -O https://raw.githubusercontent.com/michjak-szymanski/orknux-server/main/deploy/compose.yaml
export ORKNUX_SECRET_KEY="$(openssl rand -base64 32)"
docker compose up -d
```

Then open **http://localhost:8080** and sign in as `alice` / `password`.

The first start takes a minute or two: Temporal applies its own schema, and the
server runs its way through ninety-odd Flyway migrations before it answers
anything. `docker compose logs -f orknux-server` is where it says so.

**If you only want to look at Orknux, there is one container that needs none of
this**: `docker run -p 8080:8080 orknux/orknux-one`, no file to copy and no key
to generate. It is not a deployment and it says so - see
[One container instead](#one-container-instead-orknux-one) below for what it
gives up, starting with runs that are not durable.

## The secret key, before anything else

`ORKNUX_SECRET_KEY` is the one setting with no default, and the compose file
refuses to start without it rather than letting you find out later.

It encrypts every credential the server is trusted with - model provider keys,
Slack tokens, MCP secrets - so a copy of the database on its own is not enough
to use them. It has to be **32 bytes, base64 encoded**, which is what `openssl
rand -base64 32` produces; anything else fails when a credential is read, not
when the server starts.

There is no default on purpose. A key written into a file people copy is a key
every installation shares, which is the same as having no key at all.

**Changing it or losing it strands every secret already stored.** Nothing
decrypts them afterwards. They are not corrupted and they are not recoverable -
they simply have to be entered again, by hand, one at a time. So the key is
permanent from the moment you save the first credential, and it belongs in
whatever you already use to keep secrets, backed up somewhere other than the
database it protects.

The server reads it on first use rather than at startup, which is why the guard
is in the compose file: without it the server would boot, report itself
perfectly healthy, and fail the first time anybody pressed Save on a provider
key. Admin -> Doctor is the screen that says whether the key is set, the right
length, and whether every stored secret can still be read with it.

## What it runs, and why each thing is there

| Service | Required? | What it is for |
| --- | --- | --- |
| `postgres` | Yes, unless you use SQLite *and* drop Temporal | Everything Orknux knows. Sessions live here too, so signing in survives a restart. Temporal keeps its own state here as well, which is why dropping it takes both. |
| `ldap` | Yes, unless you use OIDC or the internal administrator below | Somewhere to sign in against. Orknux keeps its own users too, but nothing seeds the first one for you. |
| `temporal` | Yes, as configured here | What makes a run durable: it survives a restart, retries a step, and can be looked at afterwards. |
| `orknux-server` | Yes | What you open: the interface, the API and the engine. It also serves the manual at `/docs`. |
| `temporal-ui` | No, `debug` profile | Temporal's own web interface, for reading the history of a run that went wrong. |

**One Postgres, not two.** Temporal keeps its own `temporal` and
`temporal_visibility` databases in the same server and creates them itself on
first start, so a deployment runs one database rather than two.

**LDAP is not a development convenience here.** Orknux authenticates against a
directory or against an OIDC provider - one or the other, never both, because
both would mean an LDAP password for every account the OIDC provider governs.
The default is LDAP, so without a directory the sign-in screen has nothing to
ask. What *is* a development convenience is this particular directory: `alice`
and `bob`, whose passwords are the word `password`, seeded from an LDIF written
into the compose file. Replace them (see below) before this holds anything.

A third answer is to run neither: **Signing in without a directory**, further
down, seeds one internal administrator from two environment variables, and the
`ldap` service can then go entirely.

**Temporal is not optional either**, with the configuration in this file, and
not only in the sense that runs would misbehave: the server **exits** when it
cannot reach Temporal, rather than starting and accepting work it has no way to
run. That is why the compose file waits for Temporal to report healthy before
the server is even created, and why every service is `restart: unless-stopped` -
a deployment brought up in the wrong order restarts until Temporal answers.

You can set `ORKNUX_TEMPORAL_ENABLED=false` on the server and drop the service.
Runs then happen on the calling thread, with no retries and no resumption, and a
restart mid-run loses the run. That is what the test suite does. It is not what
a deployment should do, which is why it is not the default here.

**Temporal's web interface is optional**, and it is left out because it is a
second web interface onto the same data with no sign-in of its own. Turn it on
when you need it:

```
docker compose --profile debug up -d
ORKNUX_TEMPORAL_UI_URL=http://localhost:8233 docker compose up -d
```

The second line is what makes a run inside Orknux link out to its history.

## Postgres or SQLite

Orknux keeps its state in either, and which one is `ORKNUX_DB_URL` on the server
and nothing else - the driver, the Hibernate dialect and which set of migrations
Flyway reads are all worked out from that one line.

```
ORKNUX_DB_URL: jdbc:postgresql://postgres:5432/orknux   # a server, and what this file runs
ORKNUX_DB_URL: jdbc:sqlite:/home/orknux/orknux.db       # a file, and nothing else to run
```

**Take Postgres unless you can say why not.** It takes more than one writer, it
is what the suite runs against by default, and it is where any behaviour under
load has actually been watched. It is also what this file already brings up, so
choosing it costs you nothing you are not already paying.

**SQLite is for the installation of one, or few.** There is no second container,
no password, and a backup is a file copy. Everything the test suite covers works
- signing in, workspaces, issues, agents, workflows, runs, chat, the MCP
endpoint, attachments, sessions, password resets - and the suite is run against
both. What it costs is worth knowing before rather than after:

- **One writer at a time.** SQLite takes a single write lock for the database, so
  two requests that both write are serialised rather than run together. Quick
  enough for a handful of people, and not a database to run a busy installation
  on.
- **One process, one machine.** The file is the installation. Two servers pointed
  at one file over a network share will corrupt it, so there is no second node
  and no rolling restart.
- **No time zone on a timestamp.** SQLite has no zoned type. The moment is kept
  and compares correctly; the original offset is not stored.
- **Backups are a file copy, and only when nothing is writing.** There is no
  `pg_dump` here. A running server writes `-wal` and `-shm` files beside the
  database, so copy all three together or stop the server first.

Two things to get right when you point it at a file. Put it on a volume that
outlives the container - `/home/orknux` is the one this file already mounts, and
the one the server user can write. And **the directory has to exist**: the server
creates the database file, not the folder holding it, and says which path is
missing rather than failing with something about a connection.

Using it here means setting `ORKNUX_DB_URL` on `orknux-server`, dropping
`ORKNUX_DB_USERNAME` and `ORKNUX_DB_PASSWORD` (a file has nobody to authenticate
to, and they are ignored), and removing the `postgres` entry under `depends_on:`.
The `postgres` service itself only goes if Temporal goes too: Temporal keeps its
own state there, and the `auto-setup` image this file runs is pointed at
Postgres. So the small installation that really is a file and nothing else is the
one that also sets `ORKNUX_TEMPORAL_ENABLED=false` and drops both services, with
the trade the Temporal section above describes - runs on the calling thread, no
retries, no resumption, and a restart mid-run loses the run.

The README's **The database** section in the source repository is the full list
of what differs underneath.

## One container instead: `orknux-one`

The installation described in the paragraph above - SQLite, no Temporal, no
directory - is small enough that it does not need a compose file at all, so it is
published as an image of its own.

```
docker run -d --name orknux -p 8080:8080 -v orknux-data:/var/lib/orknux orknux/orknux-one
docker logs orknux
```

The interface, the server and the database file are one container. The server
answers on `8080` and serves the interface itself, exactly as `orknux-server`
does in the compose file, which is why the browser stays on one origin.

**Nothing has to be supplied and nothing is a documented default.** On the first
start it writes a database, generates an encryption key, and creates one
administrator whose password it generates and prints:

| File in `/var/lib/orknux` | What it is |
| --- | --- |
| `orknux.db` | The database, and its `-wal` and `-shm` files while it is running. |
| `secret.key` | The 32 bytes every stored credential is encrypted with. |
| `admin-password` | What was printed on the first start. Mode `600`. |
| `attachments/` | Chat attachments. |

`docker exec orknux cat /var/lib/orknux/admin-password` reads the password back
if the log has scrolled. Change it under the account and delete that file; the
server only ever *creates* this account, so nothing puts a password back that
somebody changed. `ORKNUX_BOOTSTRAP_ADMIN_USERNAME` and
`ORKNUX_BOOTSTRAP_ADMIN_PASSWORD` choose them instead.

**`/var/lib/orknux` is the whole installation, and the key in it is why the
volume matters more here than usual.** Everything above lives in that one
directory. Back it up as a directory, with the container stopped: the database
without `secret.key` has credentials in it that nothing can read again, and no
part of that failure is visible until somebody opens a provider or a Slack
connection and finds it broken. Give the container `ORKNUX_SECRET_KEY` and it
uses that instead and writes no key file, which is what to do if you already keep
secrets somewhere better than a volume.

**What it does not do** is the part to read before mistaking it for a deployment,
and it is the whole of the trade this page has been describing in pieces:

- **No durable runs.** Nothing is retried, nothing resumes, and a restart in the
  middle of a run leaves that execution at RUNNING for ever, because there is no
  engine left to finish it or fail it.
- **A wait longer than five minutes fails by design**, with a message telling you
  to enable Temporal. "Wait a day" cannot work there.
- **Starting a run answers with a finished one**, so the API is a different shape
  from a real deployment's.
- **No LDAP and no OIDC**, so nothing about directory sign-in, group mapping or
  SSO can be tried in it.
- **SQLite**, with everything the section above lists.
- **The bundled manual at `/docs` describes the product, not this container.** It
  says runs are carried out by Temporal and retried, which is true of the
  deployment this compose file brings up and not of that image. It is left
  correct about the product rather than forked, and the difference is written
  down in `DOCKERHUB-ONE.md` and here.

Use it to try Orknux, to develop against, and to demonstrate it. Use this compose
file for anything whose runs matter.

## Signing in without a directory

Orknux keeps its own users - people it made up rather than people a provider
vouches for - and they sign in with a password on the ordinary sign-in form,
whatever `ORKNUX_AUTH_METHOD` says. What it cannot do on its own is make the
first one: an account is created by an administrator, or written down when a
provider vouches for somebody at the door, so an installation with no directory
and no OIDC has nobody to create the administrator who could create you.

Two variables on `orknux-server` close that circle:

```
ORKNUX_BOOTSTRAP_ADMIN_USERNAME=admin
ORKNUX_BOOTSTRAP_ADMIN_PASSWORD=a long password you chose
```

On the next start, if no user has that name, one internal user is created with
that password and the built-in `Administrators` role - the same role the Roles
screen shows and the same sign-in everybody else uses. Nothing is loosened to let
it in.

Then the `ldap` service can go: delete it, and delete the `ldap:` entry under
`depends_on:` on `orknux-server` as well, or the server waits for a service that
no longer exists. Leave `ORKNUX_AUTH_METHOD` at `LDAP` - internal users are
checked before the directory is ever called, so the sign-in form works with
nothing behind it. Everybody else is then somebody you create under **Admin ->
Users**, with a password you set there.

The password has to be at least 12 characters, the same minimum every other way
of setting one holds to. A shorter one seeds nobody and says so in the log,
rather than making an account that could never be signed in to.

**It only ever creates.** A user of that name that already exists is left exactly
as it is, password and roles alike, and the log says it was left alone. So
leaving the variables set cannot reset a password somebody has since changed or
put back a role somebody deliberately took away, and a restart is not a way for
anyone who can edit this file to take over an account they do not own.

**A password in an environment variable is a compromise, and it is one on
purpose.** It is readable by anything that can see the server's environment -
`docker inspect`, another process in the container, the `.env` file sitting next
to `compose.yaml` - and it stays readable for as long as it is set. It is how you
get in the first time and nothing more. So, once you are in: change it from the
account's own preferences, then unset both variables and bring the server up
again. Until you do, every start says in the log that the account still has the
password from the environment.

## Turning authentication off

`ORKNUX_AUTH_METHOD=NONE` is the one value that opens the front door. Nobody
signs in, no screen asks, and every request that reaches the server acts as one
identity - an ordinary internal user called `everyone`, holding the built-in
`Administrators` role. **Anyone who can reach the port then administers this
installation**, sees every workspace and can use every stored credential, so it
belongs only where something else is already the gate: a laptop somebody is
trying the product on, a VPN, an authenticating proxy in front of `orknux-server`.
Note that this compose file publishes 8080 on the host, which is not a gate.

Nothing arrives at it by accident. Unset is `LDAP`, empty is `LDAP`, and a value
that is none of the four names fails to bind and stops the server before it
answers a request. And nothing about it is quiet: the log says so at startup,
**Admin -> Doctor** warns, and a strip across the top of every page says it to
whoever is looking at the screen. Set `ORKNUX_AUTH_METHOD` back to `LDAP`,
`OIDC` or `INTERNAL` and the door closes again - the `everyone` row has no
password, so it cannot be signed in as afterwards.

## Only one port is published

`orknux-server` on 8080, and that is all. The server serves the interface as
well as `/api`, `/graphql` and `/mcp`, so the browser only ever talks to one
origin and the session cookie is first-party.

Postgres, LDAP and Temporal are not published either. Nothing outside this file
has any business connecting to them, and a database on a laptop's 5432 is a
database somebody else's tool will find. Add `ports:` yourself if you want
`psql` on it.

## Settings

Every one of these is optional and has a working default. Set them in your shell
or in a `.env` file next to `compose.yaml`.

| Variable | Default | What it changes |
| --- | --- | --- |
| `ORKNUX_SECRET_KEY` | *none, required* | See above. |
| `ORKNUX_HTTP_PORT` | `8080` | The port you open in a browser. |
| `ORKNUX_DB_PASSWORD` | `orknux` | The Postgres password, used by Postgres, the server and Temporal alike. Only read when the database volume is first created. |
| `ORKNUX_LDAP_ADMIN_PASSWORD` | `admin` | The directory's admin password, which is also what the server binds with. |
| `ORKNUX_AUTH_METHOD` | `LDAP` | `LDAP`, `INTERNAL`, `OIDC`, or `NONE` to turn authentication off - see below. |
| `ORKNUX_BOOTSTRAP_ADMIN_USERNAME` | *empty* | The first internal administrator, created at startup if nobody has that name. Empty seeds nobody. See above. |
| `ORKNUX_BOOTSTRAP_ADMIN_PASSWORD` | *empty* | What they sign in with the first time. At least 12 characters, and something to change and unset once you are in. |
| `ORKNUX_SERVER_TAG` | `0.9` | Which `orknux/orknux-server` image. |
| `ORKNUX_TEMPORAL_UI_URL` | *empty* | Where a run links out to. Empty offers no links, which is right while the Temporal UI is not running. |
| `ORKNUX_ALLOWED_ORIGINS` | *empty* | Cross-origin callers to allow. Empty is correct here, since the page and the API come from the same server. |
| `ORKNUX_TEMPORAL_UI_PORT` | `8233` | Only with `--profile debug`. |
| `ORKNUX_OIDC_ISSUER` | *empty* | The OIDC provider, by its issuer. Only read when `ORKNUX_AUTH_METHOD=OIDC`. |
| `ORKNUX_OIDC_CLIENT_ID` | *empty* | This installation, as the provider knows it. |
| `ORKNUX_OIDC_CLIENT_SECRET` | *empty* | Its secret, where the provider issued one. |
| `ORKNUX_OIDC_AUDIENCES` | *the client id* | Which audiences a bearer token may name, comma separated. **The one that can lock an upgrade out** - see below. |
| `ORKNUX_BASE_URL` | *empty* | Where this installation is reached from, as a browser spells it. It is what a mailed password reset link points at, and the host on every picture link handed to a model - a path with no host behind it resolves nowhere once a model has copied it into a message. Empty writes no reset link and sends none. |
| `ORKNUX_MAIL_HOST` | *empty* | The relay this installation sends its own mail through. Empty means it cannot, so there is no password reset. |
| `ORKNUX_MAIL_FROM` | *empty* | What that mail is from. A relay will not take a message without one. |
| `ORKNUX_MAIL_PORT` | *by security* | Empty takes 587 for STARTTLS, 465 for TLS, 25 for none. |
| `ORKNUX_MAIL_USERNAME`, `ORKNUX_MAIL_PASSWORD` | *empty* | Empty sends without authenticating, which is what an internal relay usually wants. |
| `ORKNUX_MAIL_SECURITY` | `STARTTLS` | `NONE`, `STARTTLS` or `TLS`. STARTTLS is required rather than merely offered. |
| `ORKNUX_SESSION_COOKIE_SAME_SITE` | `lax` | `strict` once nothing links into Orknux from elsewhere. |
| `ORKNUX_MARKETPLACE_URL` | `https://orknux.ai/graphql` | Where the plugin catalog is asked. The server fetches it, not the browser, so it goes out through the proxy rules. Point it at your own mirror, or set it empty on an installation with no way out: the Plugins page then offers the upload alone. |
| `ORKNUX_PLUGIN_TIMEOUT_MILLIS` | `30000` | How long a plugin call may take. The floor this installation starts at; Admin -> Settings is the switch, between 1 and 300 seconds, and what it holds wins. |
| `ORKNUX_EXECUTION_RETENTION_DAYS` | `90` | How long a finished run is kept before it is swept, with its steps. Admin -> Settings is the switch. |
| `ORKNUX_CHAT_MAX_ROUNDS` | `8` | How many rounds of tool calls an agent gets before it must answer; one call to the model is a round. What this installation starts at; Admin -> Settings is the switch, between 2 and 100, and an agent may carry its own on its page. |
| `ORKNUX_LIBRARY_REGISTRY_URL` | `https://registry.npmjs.org` | Where an administrator installing a library by name fetches from - once, into the database, through the proxy rules. Point it at your own mirror, or set it empty on an installation with no way out: the Libraries page then offers the upload alone. |

**Resetting a forgotten password needs three of those.** A reset is a link mailed
to the address on the account, good once and for an hour, and it only exists for
an internal user who already has a password - a directory or OIDC account's
password belongs to the provider. It is off until `ORKNUX_MAIL_HOST`,
`ORKNUX_MAIL_FROM` and `ORKNUX_BASE_URL` are all set, and until they are the form
still answers the same polite sentence to everybody and the log says what is
missing. That is deliberate: an answer that varied would say which addresses have
accounts here.

**`ORKNUX_OIDC_AUDIENCES` is the setting most likely to break an upgrade.** A
bearer token has to name this installation in its `aud` claim - checked since
0.5.0, where before that only the issuer was - and empty means the client id,
which is what a provider writes into a token minted for this application and is
not what two common providers write. Keycloak names
`account` unless an audience mapper is configured against this client; Entra
names the application's App ID URI. Browser sign-in is unaffected either way,
but API calls that worked yesterday answer 401, with `The aud claim is not
valid` in the server log. Either configure the provider to name this client, or
set this to what its tokens actually carry - it takes a list, and a token has to
match one of them rather than all.

Everything else the server understands is one environment variable on
`orknux-server`, all prefixed the same way, and
[DOCKERHUB.md](../DOCKERHUB.md) lists every one with what it does and what
happens if you say nothing.

## Which images, and where the tags come from

Both images - `orknux-server` and the all-in-one `orknux-one` - are published
from CI on every push to `main`, under one scheme:

- `latest` follows `main`.
- `X.Y.Z` and `X.Y` come from release tags.
- `sha-<commit>` never moves, and is the one to pin to if you want to be certain
  what you are running.

`compose.yaml` pins `0.9`, so what you bring up today is what you bring up next
week. `latest` follows `main` and moving under a running deployment is how an
upgrade happens to you rather than being something you did. Set
`ORKNUX_SERVER_TAG` to move deliberately, and to `sha-<commit>` if you want to
be certain to the commit.

Both repositories are at `0.9`/`0.9.9.10`, released together, and that is what
this file uses. The interface is built into the server image, so one tag is the
whole product and the two halves cannot be pinned apart. Check what exists
before reaching for a different one:

- https://hub.docker.com/r/orknux/orknux-server/tags
- https://hub.docker.com/r/orknux/orknux-one/tags

`orknux-one` carries the same jar and moves with it, so pin it to the same
number. It takes no tag variable here because this file does not run it - it is
the alternative to this file rather than a service in it.

`orknux/orknux-ui`, the nginx image that used to serve the interface in front
of the server, is no longer needed; see **Upgrading** for moving an
installation off it.

Both Orknux images are published for **linux/amd64 only**. They run on Apple
Silicon under Docker Desktop's emulation, slowly. Postgres, Temporal and
OpenLDAP all have native arm64 builds.

## Before this is more than a demonstration

The defaults are chosen so that the file runs when you copy it, not so that it
is safe when you leave it. In rough order of how much it will hurt to have
skipped:

1. **Keep the secret key somewhere.** Everything above.
2. **Replace the seeded people.** Delete the `alice` and `bob` entries from the
   `ldap-bootstrap` config, or point the server at a directory you already run
   by changing `ORKNUX_LDAP_URLS`, `ORKNUX_LDAP_BASE`, `ORKNUX_LDAP_BIND_DN` and
   `ORKNUX_LDAP_BIND_PASSWORD` and dropping the `ldap` service entirely. The
   bootstrap LDIF is only read when the `ldap-data` volume is empty, so editing
   it later changes nothing until that volume goes.
3. **Or use OIDC instead**, which is usually the better answer where an identity
   provider already exists: set `ORKNUX_AUTH_METHOD=OIDC`,
   `ORKNUX_OIDC_ISSUER`, `ORKNUX_OIDC_CLIENT_ID` and
   `ORKNUX_OIDC_CLIENT_SECRET`, and drop the `ldap` service. Which of the
   provider's groups grants which role is `orknux.security.role-mapping`, and it
   is YAML only - the keys are claim values full of dots and commas, and the
   environment-variable spelling of one is not something anybody should have to
   work out. Mount an `application.yml` to set it. If anything but a browser
   signs in - the CLI, a script, an assistant on the MCP endpoint - check what
   your provider writes into `aud` and set `ORKNUX_OIDC_AUDIENCES` accordingly,
   because the default is the client id and Keycloak and Entra both write
   something else.
4. **Or run neither**, with the internal administrator above, and then finish
   the job: sign in, change that password from the account's own preferences,
   and unset `ORKNUX_BOOTSTRAP_ADMIN_USERNAME` and
   `ORKNUX_BOOTSTRAP_ADMIN_PASSWORD`. A password left in the environment is
   readable by anything that can see the server, and the log says so on every
   start until it is changed.
5. **Change the passwords.** `ORKNUX_DB_PASSWORD` and
   `ORKNUX_LDAP_ADMIN_PASSWORD` are both the obvious word.
6. **Put TLS in front of it.** Terminate in your own proxy and forward to
   `orknux-server` on 8080. Make sure that proxy sets `X-Forwarded-For` and
   `X-Forwarded-Proto`, or the audit log attributes every action to the proxy
   rather than to the person. Then set
   `ORKNUX_SESSION_COOKIE_SAME_SITE=strict` if nothing links into Orknux from
   elsewhere; leave it `lax` if Slack or an email is expected to link somebody
   straight to a run.
7. **Stop using `temporalio/auto-setup`.** It applies the Temporal schema on
   every start, which is what makes it a one-line dependency here and what makes
   it wrong to keep: a restart should not be a migration. Run
   `temporalio/server` and apply the schema yourself with `temporal-sql-tool`.
8. **Back up the Postgres volume**, and back up the secret key separately from
   it. A backup of the database without the key restores everything except the
   credentials, which is a restore that does not work.

## Where the data lives

Four named volumes, so nothing depends on a path on your machine:

| Volume | Holds |
| --- | --- |
| `postgres-data` | Everything Orknux and Temporal know. |
| `ldap-data`, `ldap-config` | The directory, if you are using the one in this file. |
| `orknux-data` | Attached files, under `/home/orknux/attachments`. |

`orknux-data` is mounted at the server user's home directory rather than at
something tidier like `/app/data`, and that is not a style choice. The image
runs as `orknux` rather than as root; a named volume mounted on a path the image
does not already contain is created owned by root, and the server then cannot
write a single attachment. `/home/orknux` exists in the image and belongs to
that user, so the volume inherits the ownership and works. If you move it, move
it somewhere that user can write.

`docker compose down -v` removes all four. That is how you start over, and it is
also how you lose everything.

## Upgrading

```
docker compose pull
docker compose up -d
```

Flyway migrates on the way up, so the schema follows the server. JPA runs with
`ddl-auto: validate`, which means the migrations are the only thing that ever
changes the database and a mismatch is a startup failure rather than a strange
query. Take the Postgres volume first if you would mind going back.

### Updating in place, and turning it off

An administrator can also update from **Admin -> Updates**: a release from the
official server, an uploaded jar, or a jar fetched from a URL, checked against
the release key the image carries, kept in the database and restarted into - and
rolled back the same way. A newer image always wins over a release chosen under
an older one, so the two commands above still upgrade. It is **on by default**;
set `ORKNUX_SELF_UPDATE: "false"` on `orknux-server` where every version that
runs has to be approved, and the image's own jar always runs. On a read-only
root filesystem, give `/tmp/orknux-release` (`ORKNUX_RELEASE_DIR`) a tmpfs.

Each source has its own switch under that one, all `true` by default:
`ORKNUX_SELF_UPDATE_OFFICIAL`, `ORKNUX_SELF_UPDATE_UPLOAD` and
`ORKNUX_SELF_UPDATE_URL`. `false` refuses that source, and a release it brought
in earlier can no longer be started; the image's own jar always can.

To take releases only from your own repository, point
`ORKNUX_RELEASE_SOURCE_URL` at a generic Artifactory repository the platform team
fills - a jar's URL, or a directory ending in `/` with a `releases.json` beside
the jars (`[{"version": "0.9.9.10", "jarUrl": "orknux-server-0.9.9.10.jar"}]`) -
and set `ORKNUX_SELF_UPDATE_OFFICIAL` and `ORKNUX_SELF_UPDATE_UPLOAD` to
`"false"`. The page fills its URL field from it; a token or `user:password` typed
beside it is sent to that host and stored nowhere. The fetch goes through the
proxy rules and trusted authorities on Admin -> Networking, and the jar is
verified exactly as an upload is: the repository is trusted to have it, never
for what is in it.

A release jar is about 350 MB, and Admin -> Settings caps it at 1024 MB. A proxy
in front of the server has its own cap on a request body and answers 413 before
the server sees the upload - the old `orknux-ui` container below among them, and
an ingress-nginx at `proxy-body-size`. Raise it to the jar limit, or point the
proxy straight at `orknux-server`.

### From an installation that still runs orknux-ui

Until #585 the interface was a second image, `orknux/orknux-ui`: nginx serving
the bundle and forwarding `/api`, `/graphql` and `/mcp` to the server, which
published no port of its own. The server image now carries the interface and
serves it itself, so that container is no longer needed. One that is still
running keeps working - the server still answers every path it forwards - so
this can wait for a convenient moment:

1. Pull a server release that serves the interface.
2. Point whatever reached `orknux-ui` - the published port, a reverse proxy, an
   Ingress - at `orknux-server` on 8080. With this file, that is the `ports:`
   entry moving from the `orknux-ui` service to `orknux-server`; copying the
   new `compose.yaml` does it.
3. Remove the `orknux-ui` service, and `ORKNUX_UI_TAG` from your `.env`.
   `docker compose up -d --remove-orphans` stops the old container.

## When it does not come up

- **`required variable ORKNUX_SECRET_KEY is missing a value`** - the guard doing
  its job. Export one, as above.
- **The server sits in `starting`** - it waits for Postgres, the directory and
  Temporal to report healthy before it is even created, and then runs the
  migrations. Its healthcheck allows a minute before it starts counting.
- **The server exits and comes back, over and over** - almost always Temporal.
  It refuses to start rather than accept work it cannot run, so the loop is the
  design working; `docker compose logs temporal` is where the reason is.
- **Sign-in is refused** - `docker compose logs ldap` and check the bootstrap
  ran. It is only applied when `ldap-data` is empty, so a volume left over from
  an earlier attempt with different settings will not have it.
- **Everything works until you save a provider key** - the secret key is wrong
  rather than missing. Admin -> Doctor says which.

## On Kubernetes instead

The same deployment — the same four services, the same images, the same
arrangement — is written as Kubernetes objects in
[`kubernetes/orknux.yaml`](kubernetes/orknux.yaml).

```
kubectl create namespace orknux
kubectl -n orknux create secret generic orknux-secret-key \
  --from-literal=secret-key="$(openssl rand -base64 32)"
kubectl apply -f kubernetes/orknux.yaml
kubectl -n orknux port-forward svc/orknux-server 8080:8080
```

**This page is the other half of that one and is not repeated there.**
Everything above about what each service is for, whether it is genuinely
required, Postgres or SQLite, pointing the server at your own directory or at an
OIDC provider, signing in without either, and the table of settings is true
whatever runs the containers. [`kubernetes/README.md`](kubernetes/README.md) is
what is different because it is Kubernetes: the secret key as an object the
manifest deliberately does not carry, an init container in place of
`depends_on`, probes that ask for a URL because kubelet is the one asking rather
than something inside an image that has no curl in it, why the server is one
replica and what Slack has to do with that, and what an ingress controller has
to be told before a 25MB attachment can be uploaded through it.

## This is not the development stack

The [`compose.yaml` at the repository root](../compose.yaml) is a different
thing and is not a smaller version of this one. It brings up only the
dependencies - Postgres, OpenLDAP and Temporal - because the server itself is
expected to be running from Maven on the host, and it publishes their ports so
that it can. Use that one to work on Orknux; use this one to run it.
