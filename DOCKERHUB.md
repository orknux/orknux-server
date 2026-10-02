# orknux-server

Workspace-based agent orchestration: workflows drawn as a graph and run durably,
with the agents, models, connections and credentials a workspace holds, plugins
installed from a marketplace, and an issue tracker an assistant can work through
over MCP.

This image is the whole product - the interface, the API and the engine - on
8080. **It replaces `orknux/orknux-ui`**: point the port, proxy or Ingress that
reached that at this container and remove it. Until then it keeps working.

- **Source:** https://github.com/michjak-szymanski/orknux-server
- **Licence:** AGPL-3.0-or-later
- **Exposes:** `8080`
- **Runs as:** `orknux`, not root
- **Tags:** `latest` follows `main`; `X.Y.Z` and `X.Y` come from release tags;
  `sha-<commit>` never moves.

## What it needs

A database - Postgres, or SQLite and no second container - something to sign in
against (a directory, an OIDC provider, or its own accounts), and Temporal. By
default it **refuses to start** without Temporal, so one brought up before its
Temporal restarts until that service answers.

```yaml
services:
  orknux-server:
    image: orknux/orknux-server:latest
    ports: ["8080:8080"]
    environment:
      ORKNUX_SECRET_KEY: "a-32-byte-key-you-generated"     # see below
      ORKNUX_DB_URL: jdbc:postgresql://postgres:5432/orknux
      ORKNUX_DB_USERNAME: orknux
      ORKNUX_DB_PASSWORD: orknux
      ORKNUX_LDAP_URLS: ldap://ldap:389
      ORKNUX_TEMPORAL_TARGET: temporal:7233
      ORKNUX_ATTACHMENTS_LOCATION: /home/orknux/attachments
    volumes:
      - orknux-data:/home/orknux   # only if attachments are on
```

The volume sits on the server user's home directory on purpose: a named volume
on a path the image does not contain is created owned by root, and this image
runs as `orknux`, which then cannot write to it. Move it only somewhere that
user can write.

A whole installation - database, directory, Temporal - is
[`deploy/compose.yaml`](https://github.com/michjak-szymanski/orknux-server/blob/main/deploy/compose.yaml)
in the source repository.

## The one that matters most

| Variable | What it does | Default | Required |
| --- | --- | --- | --- |
| `ORKNUX_SECRET_KEY` | Encrypts every credential this server holds - provider keys, Slack tokens, MCP secrets - so the database alone is not enough to use them. 32 bytes, base64. | *none* | **Yes** |

A key that is set and **cannot work** - not valid base64, or the wrong length -
stops the boot, loudly. Otherwise it surfaces as a 500 hours later, while the
installation serves pages and every credential it holds is quietly unusable. A
*missing* key still boots: that is a first run with nothing encrypted yet, and
refusing would hide the screen explaining what to set.

There is deliberately **no default**: a key committed to an image is one every
installation shares, which is the same as no key. Generate one with `openssl
rand -base64 32` and keep it somewhere other than the database it protects.
**Changing or losing it makes every stored credential unreadable**, and they
have to be entered again. Admin -> Doctor says whether the key is set, the right
length, and whether every stored secret still reads with it.

## Database

Postgres or SQLite. `ORKNUX_DB_URL` decides which and nothing else does: the
driver, dialect and migrations follow from it. Postgres is for a deployment;
SQLite is one file with nothing else to run, for an installation of one or a
few. What that costs is in the README's **The database**.

Under SQLite, put the file on a volume that outlives the container and make the
directory yourself: the server creates the file, not the directory.

```
ORKNUX_DB_URL: jdbc:sqlite:/data/orknux.db
```

The schema is Flyway's either way, and the migrations are the only thing that
ever changes it.

| Variable | What it does | Default | Required |
| --- | --- | --- | --- |
| `ORKNUX_DB_URL` | The JDBC URL, and what picks the database. `jdbc:postgresql://host:5432/orknux` or `jdbc:sqlite:/data/orknux.db`. | `jdbc:postgresql://localhost:5432/orknux` | **Yes** in a deployment - the default is localhost |
| `ORKNUX_DB_USERNAME` | The user it connects as. Ignored under SQLite. | `orknux` | **Yes** in a Postgres deployment |
| `ORKNUX_DB_PASSWORD` | That user's password. Ignored under SQLite. | `orknux` | **Yes** in a Postgres deployment |
| `ORKNUX_DB_MIGRATE` | Whether Flyway migrates on start. Turn it off only where something else owns the schema, which this build expects at its own version. | `true` | No |

## Signing in

`ORKNUX_AUTH_METHOD` picks one, and only one - never two at once.
[The README's **Access** section](https://github.com/michjak-szymanski/orknux-server/blob/main/README.md#access)
is why.

| Variable | What it does | Default | Required |
| --- | --- | --- | --- |
| `ORKNUX_AUTH_METHOD` | `LDAP`, `INTERNAL`, `OIDC`, or `NONE` to turn authentication off. | `LDAP` | No |
| `ORKNUX_ADMIN_ROLE` | The role that sees the Admin section and every workspace. | `ROLE_ADMINS` | No |

**How hard somebody may try.** A wrong password costs nothing until the
allowance is spent, then a pause doubling to a ceiling; nothing locks anybody
out. The five `ORKNUX_SIGN_IN_*` and six `ORKNUX_PASSWORD_RESET_*` variables are
in
[the README's **Rate limits**](https://github.com/michjak-szymanski/orknux-server/blob/main/README.md#rate-limits),
and their defaults suit almost every installation.

**INTERNAL** - nothing to configure, which is the point. Username and password
against this installation's own accounts, no directory or provider contacted. It
is what `orknux/orknux-one` runs on, and what to set when the bootstrap
administrator below is the way in.

**NONE** - authentication off. Every request acts as one identity that
administers, so anyone reaching the port administers this installation: run it
only behind a gate of your own. Never a fallback - an unrecognised value stops
the server - and said at startup, on Doctor and across every page.

**LDAP** - read only when `ORKNUX_AUTH_METHOD=LDAP`.

| Variable | What it does | Default | Required |
| --- | --- | --- | --- |
| `ORKNUX_LDAP_URLS` | The directory to bind to. | `ldap://localhost:389` | **Yes** under LDAP |
| `ORKNUX_LDAP_BASE` | The root of the tree everything below is relative to. | `dc=orknux,dc=io` | **Yes** under LDAP |
| `ORKNUX_LDAP_BIND_DN` | The account this server binds as to search. | `cn=admin,dc=orknux,dc=io` | **Yes** under LDAP |
| `ORKNUX_LDAP_BIND_PASSWORD` | That account's password. | `admin` | **Yes** under LDAP |
| `ORKNUX_LDAP_USER_SEARCH_BASE` | Where people are looked for, under the base. | `ou=people` | No |
| `ORKNUX_LDAP_USER_SEARCH_FILTER` | How a typed username is matched; `{0}` is what was typed. | `(uid={0})` | No |
| `ORKNUX_LDAP_GROUP_SEARCH_BASE` | Where groups live. Workspace groups must sit under this base to be picked up; empty disables group search. | `ou=groups` | No |
| `ORKNUX_LDAP_GROUP_SEARCH_FILTER` | How a person's groups are found; `{0}` is their DN. | `(member={0})` | No |

**OIDC** - read only when `ORKNUX_AUTH_METHOD=OIDC`. Two flows: a browser returns
from the provider with a code and gets the session cookie a password sign-in
issues; a script presents the provider's token as a bearer, validated per
request.

| Variable | What it does | Default | Required |
| --- | --- | --- | --- |
| `ORKNUX_OIDC_ISSUER` | The provider. Its discovery document gives the endpoints and the keys signing its tokens. | *none* | **Yes** under OIDC |
| `ORKNUX_OIDC_CLIENT_ID` | This installation, as the provider knows it. | *none* | **Yes** under OIDC |
| `ORKNUX_OIDC_CLIENT_SECRET` | Its secret. | *none* | **Yes** under OIDC |
| `ORKNUX_OIDC_SCOPES` | What is asked for, comma separated. | `openid,profile,email,groups` | No |
| `ORKNUX_OIDC_USERNAME_CLAIM` | The claim to show as somebody's name. The subject is the fallback. | `preferred_username` | No |
| `ORKNUX_OIDC_ROLES_CLAIM` | The claim carrying group or role membership; there is no standard one. Keycloak and Okta usually say `groups`, Entra `groups` or `roles`. | `groups` | No |
| `ORKNUX_OIDC_DISPLAY_NAME` | What the sign-in button says. | `single sign-on` | No |
| `ORKNUX_OIDC_AUDIENCES` | Which audiences a bearer token may name, comma separated. **Read the paragraph below before upgrading an OIDC installation.** | *the client id* | **Conditional** - see below |

**`ORKNUX_OIDC_AUDIENCES` locks scripts out**, because Keycloak and Entra do not
write the audience an empty setting expects - a 401 on every API call while
browser sign-in carries on working.
[The README's **Access** section](https://github.com/michjak-szymanski/orknux-server/blob/main/README.md#access)
has what each writes and what to set, and covers `orknux.security.role-mapping`,
which is YAML only.

**The first administrator.** With neither a directory nor OIDC there is nobody
to create the administrator who could create you. Set both and one is made at
startup, with the `Administrators` role and a password on the ordinary sign-in
form - which internal users may always use. It only creates, so an account of
that name is untouched. A password in a variable is a way in, not one to keep:
sign in, change it, unset both.
[`deploy/README.md`](https://github.com/michjak-szymanski/orknux-server/blob/main/deploy/README.md#signing-in-without-a-directory)
has the rest.

| Variable | What it does | Default | Required |
| --- | --- | --- | --- |
| `ORKNUX_BOOTSTRAP_ADMIN_USERNAME` | The first administrator's username. Empty seeds nobody. | *none* | **Yes** with no directory and no OIDC |
| `ORKNUX_BOOTSTRAP_ADMIN_PASSWORD` | What they first sign in with. At least 12 characters; shorter seeds nobody. | *none* | With the above |

**Resetting a forgotten password** - a link mailed to the account's address, good
once and for an hour, internal users only: a directory or OIDC password belongs
to the provider. Off until the mail server below and `ORKNUX_BASE_URL` are set;
the log says what is missing.

| Variable | What it does | Default | Required |
| --- | --- | --- | --- |
| `ORKNUX_MAIL_HOST` | The relay this server sends its own mail through - not a workspace's. Empty means no password reset and no issue mail. | *none* | **Yes** to send mail |
| `ORKNUX_MAIL_FROM` | What the mail is from. | *none* | **Yes** to send mail |
| `ORKNUX_MAIL_PORT` | Empty takes what the security below usually listens on: 587, 465 or 25. | *by security* | No |
| `ORKNUX_MAIL_USERNAME` | Empty sends without authenticating. | *none* | No |
| `ORKNUX_MAIL_PASSWORD` | That account's password. | *none* | No |
| `ORKNUX_MAIL_SECURITY` | `NONE`, `STARTTLS` or `TLS`. STARTTLS is required rather than offered, so a server that stopped offering it is refused. | `STARTTLS` | No |

## Runs

Temporal is what makes a run durable: it survives a restart, retries a step, and
can be looked at afterwards.

What a trigger, a schedule or the API runs is the workflow **as published** -
Run in the editor is the one thing using the draft. Its nodes hold the *id* of
the function or agent they call, read when the step runs, so editing one changes
what a published workflow does with no republish. The README's **Publishing**
has the rest.

| Variable | What it does | Default | Required |
| --- | --- | --- | --- |
| `ORKNUX_TEMPORAL_ENABLED` | `false` runs a workflow on the calling thread with no retries or resumption, for a single-process installation with no Temporal. A deployment should not. | `true` | No |
| `ORKNUX_TEMPORAL_TARGET` | Host and port of the Temporal frontend. | `localhost:7233` | **Yes** in a deployment, unless Temporal is off |
| `ORKNUX_TEMPORAL_NAMESPACE` | The Temporal namespace to run in. | `default` | No |
| `ORKNUX_TEMPORAL_TASK_QUEUE` | The queue workers take work from. Change it to run two installations against one Temporal. | `orknux-workflow` | No |
| `ORKNUX_TEMPORAL_RUN_TIMEOUT_HOURS` | How long a whole run may take, waits included. | `24` | No |
| `ORKNUX_TEMPORAL_STEP_TIMEOUT_SECONDS` | How long one step's own work may take. It does not bound a *wait*, which parks the step. | `300` | No |
| `ORKNUX_TEMPORAL_STEP_ATTEMPTS` | How many times the platform tries a failing step. A node's own retry policy is separate. | `3` | No |
| `ORKNUX_TEMPORAL_UI_URL` | Temporal's own web interface, linked out to from a run. Empty offers no links. | `http://localhost:8233` | No |
| `ORKNUX_INLINE_MAX_WAIT` | Inline engine only: how long a run may stay parked before the step fails and says what would have carried it. A Temporal wait is a timer, bounded by the run timeout. | `5m` | No |
| `ORKNUX_TASK_MAX_TURNS` | How often a task's agent may be asked before stopping, unless the workspace sets its own. Copied onto a task at creation, as is the next, so a change spares one running. | `40` | No |
| `ORKNUX_TASK_WORKING_TIME` | The longest a task may be *working*. Not wall clock: time parked waiting to be approved counts for none. | `2h` | No |
| `ORKNUX_TASK_PATIENCE` | How long a parked task waits for a person. | `7d` | No |
| `ORKNUX_TASK_SWEEP_MINUTES` | How long a task may sit at Queued before being handed over again, so a restart strands nothing. An installation carrying its own tasks sets this on Admin -> Settings instead. | `5` | No |
| `ORKNUX_EXECUTION_RETENTION_DAYS` | How long a finished run is kept, with its steps. Admin -> Settings is the switch; this is the floor. | `90` | No |
| `ORKNUX_EXECUTION_SWEEP_ENABLED` | `false` sweeps nothing on a timer. | `true` | No |
| `ORKNUX_REVISION_RETENTION_DAYS` | How long a replaced version of a function, tool, skill or agent is kept, from when it stopped being current. Admin -> Settings is the switch. | `14` | No |
| `ORKNUX_REVISION_SWEEP_ENABLED` | `false` keeps every version forever. | `true` | No |
| `ORKNUX_REVISION_SWEEP_INTERVAL` | How often that sweep runs. | `6h` | No |
| `ORKNUX_SCHEDULER_ENABLED` | The clock behind scheduled triggers. Its state is in the database, so one instance fires a schedule however many are running. | `true` | No |
| `ORKNUX_SCHEDULER_POLLING_INTERVAL` | How often it looks for due work. | `10s` | No |
| `ORKNUX_SCHEDULER_THREADS` | How many due schedules it may start at once. | `4` | No |
| `ORKNUX_SCHEDULER_TICK_INTERVAL` | How often scheduled triggers are looked at: the finest schedule this installation keeps. A cron of seconds still fires only on a tick. | `10s` | No |

## What a workspace's code may do

A GraalJS sandbox with no host access, files, sockets or threads. One global,
`orknux`, is all it reaches outside - the server makes the call, so a function's
HTTP request obeys the proxy rules above. Memory is bounded without a variable:
a call still allocating while the heap stays nearly full after a collection is
stopped.


| Variable | What it does | Default | Required |
| --- | --- | --- | --- |
| `ORKNUX_SCRIPT_TIMEOUT_MILLIS` | A function or tool that runs longer is stopped, unless it or its workspace set one. | `5000` | No |
| `ORKNUX_SCRIPT_STATEMENT_LIMIT` | How many statements one may execute - what catches a loop that never ends. | `5000000` | No |
| `ORKNUX_SCRIPT_LOG_LEVEL` | The lowest level `orknux.log` keeps, decided inside the sandbox: `debug`, `info`, `warn`, `error`, `off`. | `info` | No |
| `ORKNUX_PLUGIN_LOG_LEVEL` | The same, for plugins. Separate so debugging one does not turn up every function. | `info` | No |
| `ORKNUX_PLUGIN_TIMEOUT_MILLIS` | The same for a plugin, which is a bundle and takes longer to load. The floor: Admin -> Settings is the switch, 1 to 300 seconds. | `30000` | No |
| `ORKNUX_PLUGIN_STATEMENT_LIMIT` | The same, for a plugin. | `10000000` | No |
| `ORKNUX_HTTP_REQUEST_TIMEOUT_SECONDS` | How long a workflow's own HTTP request may take. | `30` | No |
| `ORKNUX_LIBRARY_REGISTRY_URL` | Where installing a library by name fetches from - once, into the database, through the proxy rules. Empty offers the upload alone. | `https://registry.npmjs.org` | No |
| `ORKNUX_LIBRARY_REGISTRY_TIMEOUT` | How long it has to answer. | `30s` | No |

## Plugins and the marketplace

A plugin is a bundle this installation loads and every workspace can then use:
functions, tools, skills, object shapes and the libraries it embeds. They are
written against `@orknux/plugin` and live in
[orknux-extension](https://github.com/michjak-szymanski/orknux-extension), so a
new plugin is not a new server.

The Plugins screen carries a catalog beside what is installed. The **server**
fetches it, so it goes out through the proxy rules like every other outbound
call, and what arrives is checked against the digest the catalog published
before anything is loaded.

| Variable | What it does | Default | Required |
| --- | --- | --- | --- |
| `ORKNUX_MARKETPLACE_URL` | Where the catalog is asked. Point it at your own; empty offers the upload alone. | `https://orknux.ai/graphql` | No |
| `ORKNUX_INSTALL_KEY` | What this installation says to be answered at all. The shipped key is a shared one; an installation that wants its own asks for one. | a shared key | No |

## Models and connections

| Variable | What it does | Default | Required |
| --- | --- | --- | --- |
| `ORKNUX_MODEL_TIMEOUT` | How long a model has to answer. Generous: a large local model on a laptop is slow. | `2m` | No |
| `ORKNUX_MODEL_CHECK_ENABLED` | Periodically asks each provider whether it still answers, so the status on the screen is recent. | `true` | No |
| `ORKNUX_MODEL_CHECK_INTERVAL` | How often that sweep runs. | `5m` | No |
| `ORKNUX_MODEL_CHECK_INITIAL_DELAY` | How long after start the first sweep waits. | `30s` | No |
| `ORKNUX_CONNECTION_CHECK_ENABLED` | The same, for connections. | `true` | No |
| `ORKNUX_CONNECTION_CHECK_INTERVAL` | How often connections are checked. | `5m` | No |
| `ORKNUX_CONNECTION_CHECK_INITIAL_DELAY` | How long the first check waits. | `30s` | No |
| `ORKNUX_CONNECTION_PROBE_TIMEOUT_SECONDS` | How long a check may take to find out whether anything is listening. | `5` | No |
| `ORKNUX_CONNECTION_ALLOW_LINK_LOCAL` | Link-local addresses reach cloud instance metadata, so they are refused; turning this on lets a workspace's connection reach them. Private and loopback stay reachable either way. | `false` | No |
| `ORKNUX_CONNECTION_ENTRA_AUTHORITY` | Where an Entra ID token is asked for. The worldwide cloud - a tenant in a sovereign cloud has an address of its own. | `https://login.microsoftonline.com` | No |
| `ORKNUX_SLACK_ENABLED` | Opens one Socket Mode websocket per Slack connection holding an app-level token, and turns arriving mentions into workflow runs. | `true` | No |
| `ORKNUX_SLACK_RECONCILE_SECONDS` | How often open sockets are compared with stored connections, so a token pasted into the settings form starts listening without a restart. | `30` | No |
| `ORKNUX_SLACK_RETRY_FAILED_SECONDS` | How long a connection Slack refused is left alone. Changing the token clears the wait. | `300` | No |

**A workspace's mail is not configured here.** The `ORKNUX_MAIL_*` variables
above are the installation's own relay; mail a *workflow* sends is a connection
like any other, on the workspace's form.

**A proxy is a rule, not an environment variable.** Rules live on **Admin ->
Networking**, match the request URL, and carry every outbound call - model
providers, connections, Slack, OIDC, a function's `orknux.http`, the relay
above. Each client is given them explicitly, so an `HTTPS_PROXY` in the
environment is used by none of them. LDAP is not HTTP and cannot be carried by
a rule at all.

## Chat, attachments and the tracker

| Variable | What it does | Default | Required |
| --- | --- | --- | --- |
| `ORKNUX_CHAT_ENABLED` | Whether this installation has a chat at all. `false` is final: an administrator may turn the chat off from the screen, but not back on where the operator said no. | `true` | No |
| `ORKNUX_ATTACHMENTS_ENABLED` | Whether files may be attached at all - to a chat message, an issue, or a comment on one. `false` is final in the same way, and hides the upload controls without hiding files already uploaded. | `true` | No |
| `ORKNUX_ATTACHMENTS_LOCATION` | Where the bytes go, one directory per workspace. **A relative path resolves against the working directory**, wrong in a container: give an absolute one on a volume, or attachments go with it. | `data/attachments` | **Yes** if attachments are on |
| `ORKNUX_ATTACHMENTS_MAX_FILE_SIZE_MB` | The largest file that will be accepted. | `25` | No |
| `ORKNUX_UPLOAD_MAX_FILE_SIZE` | The servlet's own cap on one uploaded file. Keep it at or above the attachment cap, or the larger limit is never reached. | `25MB` | No |
| `ORKNUX_UPLOAD_MAX_REQUEST_SIZE` | The cap on a whole upload request. | `26MB` | No |

One switch and one directory for both: the tracker's attachments are the chat's,
and it needs nothing else configured here.

ORKNUX_CHAT_MAX_ROUNDS, ORKNUX_CHAT_MAX_SUBAGENTS, ORKNUX_CHAT_TOOLS_NAMED_IN_SEARCH,
ORKNUX_COMMAND_MARKER, ORKNUX_LDAP_GROUP_SEARCH_SUBTREE,
ORKNUX_SESSIONS_ACTIVE_WINDOW_SECONDS, ORKNUX_SESSIONS_DUE_SWEEP, ORKNUX_TASK_SWEEP_ENABLED
and ORKNUX_TASK_SWEEP_INITIAL_DELAY are in
[the README](https://github.com/orknux/orknux-server/blob/main/README.md#more-settings).

## Sessions, HTTP and logging

| Variable | What it does | Default | Required |
| --- | --- | --- | --- |
| `ORKNUX_PORT` | The port this server listens on inside the container. | `8080` | No |
| `ORKNUX_ALLOWED_ORIGINS` | Where the interface is served from, when it is not this server. Comma separated; empty allows none, which is right once they share an origin. | `http://localhost:5173` | **Yes** where the interface is elsewhere |
| `ORKNUX_BASE_URL` | Where the interface is reached from, as a browser spells it: what a mailed reset link points at, and the host on every picture link handed to a model. Configured rather than read off the `Host` header, which a caller writes. | `http://localhost:5173` | **Yes** for password resets |
| `ORKNUX_WEBHOOK_MAX_BODY_SIZE` | The most a webhook caller may post to `/api/webhooks/…`. That endpoint is open by necessity, so more is refused with 413 before any trigger runs. | `1MB` | No |
| `ORKNUX_ASYNC_REQUEST_TIMEOUT` | How long a request answered with a promise may stay open; the container's own thirty seconds would cut off the five minutes `orknux_news` may wait. | `330s` | No |
| `ORKNUX_SESSION_TIMEOUT` | How long a session survives without being used. A fortnight suits a self-hosted tool behind an identity provider; shorten it otherwise. | `14d` | No |
| `ORKNUX_SESSION_COOKIE_SAME_SITE` | `strict` where the interface shares this origin and nothing links into it; `lax` is what lets a link from elsewhere arrive signed in. | `lax` | No |
| `ORKNUX_SESSION_COOKIE_HTTP_ONLY` | Keeps the session cookie out of reach of scripts. | `true` | No |
| `OPENAI_LOG` | What the model SDK prints to stderr: `info` for each call's method and URL, `debug` for headers and bodies with credentials redacted. Set it when the question is which URL a provider was called at. | *none* | No |
| `ORKNUX_LOG_LEVEL` | How much this application says: `TRACE` to `ERROR`. Its own code only - triggers, model calls, task handover - and not the frameworks. | `INFO` | No |
| `ORKNUX_LOG_LEVEL_ROOT` | The same for everything else on the classpath, for when what is wrong is underneath this application. `DEBUG` here is very loud. | `INFO` | No |
| `ORKNUX_LOG_FORMAT` | `plain` reads well in a terminal; `json` (one ECS object per line) is what a collector wants. Applies to console and file alike. | `plain` | No |
| `ORKNUX_LOG_FILE` | Console always; name a file here and it is written to as well. Use an absolute path. | *none* (stdout only) | No |
| `ORKNUX_LOG_MAX_FILE_SIZE` | When the log file rolls. Only consulted when a file is being written. | `10MB` | No |
| `ORKNUX_LOG_MAX_HISTORY` | How many rolled files are kept. | `14` | No |
| `ORKNUX_LOG_TOTAL_SIZE_CAP` | The ceiling on all of them together. | `1GB` | No |
| `ORKNUX_METRICS_ANONYMOUS` | Whether `/actuator/prometheus` answers an unauthenticated caller. A scrape describes the installation, so `true` only where the scraper alone is on that network. | `false` | No |
| `JAVA_OPTS` | Passed to the JVM. The default gives the heap three quarters of the container's memory limit. | `-XX:MaxRAMPercentage=75` | No |

Sessions are kept in the database, so signing in outlives a restart and more
than one replica.

## What is deliberately not configurable

The schema validation mode, Flyway's locations, and Spring AI's schema
initialisation: invariants this application is built around rather than
choices - changing one does not configure the server, it breaks it.
