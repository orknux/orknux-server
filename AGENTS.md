# Working in orknux-server

Notes for anyone — human or agent — changing this repository. See
[README.md](README.md) for what the service is and how to run it.

## The name

**Orknux**. It is a coined word rather than a derivation, so there is no
etymology to tell — it was chosen for how it sounds and because it was
unclaimed. Write it in full in prose, and capitalised: it is the product's name,
not a command.

**`orkx` is the short form, and it belongs to identifiers rather than to prose.**
It is what appears where the full name would be long or where it has to be typed:
the command line client, the access token prefix `orkx_`, and the envelope an
encrypted column is kept in, `orkx1:`. A short form written into a stored value
outlives the release that chose it, which is why there is exactly one and why
nothing else should invent a second.

The identifiers have not followed the name. The Kotlin package is still
`io.mszymanski.orknux`, the Maven artifacts are still `orknux-*`, the config
properties are still `orknux.*`, and the database, its user and the LDAP base DN
are still `orknux`. Those are not cosmetic: the database name is where the
development data actually lives and the LDAP DN is what the seeded users are
under, so renaming them is a migration rather than a find-and-replace. Do not
rename them halfway.

## Commands

```
docker compose up -d                        # postgres, openldap and temporal
./mvnw spring-boot:run -pl app -am          # http://localhost:8080
./mvnw test                                 # every module, on Postgres
./mvnw test -Dtest=IntegrationAPITest       # one class
./mvnw test -Dorknux.test.database=sqlite   # the same suite, on SQLite
```

`ORKNUX_SECRET_KEY` has to be set in the environment the server is started in.
It is read on first use, so without it the application starts perfectly, reports
itself healthy, and fails the first time anything reads or writes a credential.

Three Maven modules: `app` (the deployable), `modules/connection` and
`modules/execution`. Neither module may depend on `app`; where one needs
something the app owns it declares an interface — `WorkspaceDirectory`,
`WorkflowGraphSource` — and the app implements it.

`spring-boot:run` forks a JVM; stopping the Maven process can leave it holding
port 8080. Kill the process whose command line contains `OrknuxServerKt`.

**`-am` is not optional.** `-pl app` on its own resolves the modules from the
local repository rather than from the reactor, so after a change under `modules/`
it compiles against a stale jar and fails with unresolved references to code that
is plainly there in the source. `-am` builds them alongside; `./mvnw install
-DskipTests` first is the other way out. A plain `./mvnw test` builds the whole
reactor and does not have the problem.

**The plugins are not in this repository.** They and the `@orknux/plugin`
package live in
[orknux-extension](https://github.com/michjak-szymanski/orknux-extension), and a
plugin is released when its author releases it rather than when the server is.
What is here is the half that loads one: `PluginRunner`, `PluginCapability`, the
marketplace client, and the tables a loaded plugin's functions, tools, skills,
objects and libraries live in. A change to what a plugin may *do* is a change in
both repositories, and the manifest is the contract between them.

**The interface is a submodule with its own AGENTS.md**, and its checks run in
its container - there is no Node on this machine:

```
cd orknux-ui
docker compose run --rm dev npm run typecheck   # tsc -b
```

**The interface's checks are Playwright scripts**, one per behaviour, under
`orknux-ui/scripts/*-check.mjs` and registered in `scripts/suite/suite.mjs`. They
drive a real browser against a running server and assert what is *drawn* - a
number read off `getBoundingClientRect`, a row counted in the DOM - which is the
only way a rendering bug is caught by anything but a person looking. They are
tests, not receipts: CI runs the suite, so a check written to prove one fix is a
check that keeps proving it. Write one for anything visual, and prove it fails
without the fix before keeping it.

**`npm run typecheck` is `tsc -b`, and nothing else will do.** `tsc --noEmit -p
tsconfig.json` exits 0 whatever state the code is in: the root config is
`"files": []` and a list of project references, so `--noEmit` is handed no files,
checks nothing, and succeeds on a codebase that does not compile. Reporting a
clean typecheck off that run is reporting that a command exited 0. `tsc -b`
follows the references, which is why it is the one the build and CI use.

## Stack quirks worth knowing

- **Spring Boot 4 ships Jackson 3.** The Jackson 2 Kotlin module does not apply,
  so DTOs bound from JSON need explicit `@JsonCreator` / `@JsonProperty`
  (`SessionAPI` shows the pattern). GraphQL inputs are unaffected.
- **`TestRestTemplate` is gone.** HTTP tests use `RestClient` with
  `defaultStatusHandler({ true }, { _, _ -> })` so error responses can be
  asserted on, plus `@LocalServerPort`.
- **Optional filters use JPA `Specification`s**, not JPQL. `:enum IS NULL OR …`
  fails in Hibernate 6; `WorkspaceAuditRepository.auditFilter` is the shape to copy.
- **Java 25, Kotlin 2.4.** The Maven wrapper is checked in; use `./mvnw`.
- **A Boot 3 starter may do nothing on Boot 4.** db-scheduler's
  auto-configuration is `@ConditionalOnBean(DataSource)` ordered after Boot 3's
  `DataSourceAutoConfiguration`, which moved package — so it silently never
  applied and no schedule ever fired. `TriggerSchedulerConfig` builds the
  scheduler itself, and `TriggerSchedulerIntegrationTest` starts it for real.
  Any third-party starter here deserves a test that it did something.
- `@EntityScan` moved in Boot 4: it is
  `org.springframework.boot.persistence.autoconfigure.EntityScan`.

## Schema

Flyway owns the schema and `ddl-auto` is `validate`, so every change is a new
`app/src/main/resources/db/migration/postgresql/V<n>__<name>.sql` — one history
for every module, because there is one database. Never edit a migration that has
run. Entity and migration are changed together, or the application will not start.

**Every schema change is written twice.** There are two databases: the numbered
Postgres history above, and a single squashed baseline for SQLite in
`app/src/main/resources/db/migration/sqlite/V1__baseline.sql`. SQLite has no
history to replay onto, so a new table, column or index goes into the baseline as
though it had always been there rather than as a second migration file. Write the
SQLite spelling: `INTEGER` rather than `BIGINT` for a key, because that is the
only column SQLite fills in by itself, `TIMESTAMP` rather than `TIMESTAMPTZ`, and
`BLOB` rather than `BYTEA`. `SqliteSchemaTest` starts the whole application on a
real SQLite file and is what notices when only one of the two was written; it
notices through `ddl-auto: validate`, which compares tables and columns and says
nothing about a `CHECK` constraint, so `SqliteCheckConstraintTest` reads both
files and covers that half - a value a `CHECK` allows on Postgres has to be
allowed on SQLite too.

**Know what the guards do not catch.** `SqliteCheckConstraintTest` compares the
two schemas by constraint name only, because the Postgres history also holds
constraints on tables it went on to drop and a name that no longer exists is not
drift. So a `CHECK` added to a table that already existed, and folded into only
one of the two files, slips past it - one arriving with a new table does not,
since `validate` is looking for the table itself. If a migration widens or adds a
`CHECK` on an existing table, grep the baseline for the constraint's name
yourself rather than trusting a green run.

**And neither guard reads a foreign key.** `validate` compares tables and
columns, the check test compares the literals a named `CHECK` allows, and
nothing at all compares an `ON DELETE` clause between the two files - or notices
when one contradicts a `CHECK` on the same table. That is exactly what #169 was:
`ON DELETE SET NULL` nulling a column that `ck_workflow_action_shape` requires,
which Postgres never sees because it defers the check to the end of the
statement. A guard is a list of the mistakes somebody already made; the SQLite
run is what covers the rest.

**The suite runs on two engines and CI runs both, in parallel and both
blocking.** It used to run only Postgres, so `-Dorknux.test.database=sqlite`
went where somebody remembered it - and SQLite is the engine `orknux-one` ships
with, so the engine nobody tested was the engine most people run. It went red
twice without anybody noticing: #169, a workspace SQLite refused to delete and
Postgres deleted every time, and #171, every tracker tool an agent called from a
chat answering `Unable to commit against JDBC Connection`. Neither could be seen
from a Postgres run.

The matrix sets `fail-fast: false` on purpose. What is worth knowing about a red
build is whether it is red on both engines or on one, and cancelling the
surviving leg throws away exactly that.

**Both engines are green, and a red SQLite run is now yours.** That was not true
until #171 - the old advice here was to read the failures before assuming they
were yours, because three of them were already there. They are gone, so the
opposite applies: a failure on SQLite and not on Postgres is a real difference
between the engines and is the most interesting kind of failure this repository
produces. Do not treat it as noise, which is how #169 lasted as long as it did.

`TestDatabase` is what switches them, before any Spring context exists: a
Postgres container by default, and a file in `app/target` for SQLite, which needs
no Docker. `SqliteSchemaTest` keeps a database of its own, deleted at the start of
every run - a file left behind refuses the next run with a checksum mismatch the
moment the baseline is edited, which is precisely when somebody is editing it.

Module tables carry no foreign keys across a module boundary, so a deleted workspace
is reported to the module rather than cascaded.

**A patch release migrates additively, and `rollback-floor` says when one did
not.** Since #584 an administrator can roll a server back to an older jar the
database keeps, which then starts on a schema newer than it was built for. That
works only while every migration since was additive - a new table, a nullable
column, a wider `CHECK` - because `validate` tolerates what it does not know and
Flyway ignores future migrations. A rename, a drop, a `NOT NULL` without a
default or a narrower `CHECK` breaks every older jar, so it waits for a minor
release, and the migration that does it raises
`app/src/main/resources/db/migration/rollback-floor` to its own number in the
same commit. Each jar records that file as its floor; the database's floor is the
highest floor of anything that has started on it, and a jar built for a schema
below it is refused with the reason shown. Forgetting to raise it is how a
rollback reaches a schema it cannot run on - the launcher then falls back, but
only after the restarts it is allowed.

**The start-up path is tested in a container, not in the suite.**
`docker/orknux-run.sh` (PID 1 in both images), the launcher in
`update/ReleaseLauncher.kt` and the signal forwarding between them are driven by
`scripts/self-update/lib.sh`, which both verify scripts run: a second jar signed
with a key made there, uploaded, activated, rolled back, and tampered with in the
database. The container trusts that key through `ORKNUX_RELEASE_TEST=true` plus
`ORKNUX_RELEASE_TRUST_EXTRA` (a PEM path) - **test only**, read from the process
environment and never from the database or `application.yml`, and announced on
every start it is in effect. Never set them in a deployment.

## Conventions

- **Never hand-roll what a library already does.** Before writing a line of
  protocol - an HTTP call to a vendor API, an auth handshake, a request or
  response shape, a retry policy - find the official SDK and use it. Reach for
  a hand-written client only when there is no library, and say in the commit
  why.

  This is not a style preference. Vendor APIs move: Azure OpenAI serves two URL
  shapes from one resource, ships a new API version every few months, and
  addresses a model by deployment on one surface and by name on another. A
  hand-built URL encodes today's shape and silently 404s on tomorrow's, which
  reads as a wrong configuration field and sends whoever is debugging to look
  at something that was never wrong. The SDK knows all of it, is updated by the
  people who change the API, and turns three of our bugs into a version bump.
  `com.openai:openai-java` handles Azure, Entra credentials, the v1 and
  deployment surfaces and API versions - `OpenAIOkHttpClient.builder()
  .baseUrl(...).credential(BearerTokenCredential.create(...))`.

  A library brought in this way must still come back under the proxy rules: see
  `ProxyRouter`, which exposes `proxySelector()` for exactly the clients it
  cannot build itself.

- One `@Controller` per aggregate, with `@QueryMapping` / `@MutationMapping`
  methods and the DTOs, page wrapper and exceptions in the same file. An
  `…ExceptionResolver` maps those exceptions to GraphQL error types.
- **A refusal is said twice: in English, and as something a client can
  translate.** Every `…ExceptionResolver` answers through `refused()` in
  `server/graphql/RefusedError.kt`, which keeps `message` exactly as it was and
  adds `extensions.code` - the exception's class name with `Exception` dropped -
  plus `extensions.arguments` for an exception that implements `Refusal`. The
  Polish is in the interface's bundle and nowhere here, because the same
  exception also reaches an agent through an `orknux_*` tool and an operator
  through a log, and both of those must stay English; because the thread that
  throws may have no person on it to have a language; and because every Polish
  string this product says belongs in one file somebody can read end to end.
  A resolver that builds its own `GraphQLError` sends no code and cannot be
  translated - `RefusedErrorTest` fails on one. The code is derived rather than
  declared, which is free and unambiguous right up until two classes share a
  name; seven do, and that test records which.

- **Every resolver checks access first.** `access.requireAdmin()` for
  admin-level work, `access.requireVisible(workspace)` for anything workspace-scoped.
  A resolver that loads by id resolves the owning workspace and checks that.
- **Every change writes an audit entry**, worded as the UI shows it
  ("MCP Server brave-search added"), with the right category.
- **The modules hold no notion of a user.** A controller in `app` checks access,
  calls the module service, and records the audit entry — in that order. A module
  class that wants to know who is asking has a design problem.
- **GraphQL lives in `app` only.** The modules expose services; the schema, the
  controllers and the error mapping are the app's.
- **Wiring is the app's job**: `OrknuxServer` scans, entity-scans and
  repository-scans `io.mszymanski.orknux`, because a module cannot register
  itself into a context it does not own.
- **Credentials are read in one place.** `ConnectionCredentials` resolves them
  for outbound calls and builds the `ConnectionTarget`; nothing else should
  touch a `secret` field. A Slack connection also holds an `appToken`, which is
  what Socket Mode opens the websocket with, and an SMTP connection's password
  is that same `secret` column — a second password column would be a second one
  to remember to encrypt, and a shell's private key and its passphrase are two
  more of the same. Every one of them goes through `SecretConverter`, so a new
  credential is a `@Convert` on the entity and nothing else.
- **A secret field chooses its own source, and the unit is the field.** Since
  #244 a stored credential is either its own encrypted copy or a reference to a
  workspace variable secret, held by id in a `*_variable_id` column beside it,
  with a `CHECK` making the two exclusive. `SecretReferences` is where both
  halves live — binding one on a save, reading one at the moment of use — so a
  second field on a card is a second column and a second call, never a second
  copy of the rule. Per field and not per row: a model provider has one secret
  column and a Slack connection has two, and one switch for a card cannot say
  that the bot token is a workspace secret while the app-level token is its own.
  `shell` and `proxy_rule` are installation-wide and take no reference, because
  a variable belongs to a workspace.
- **New configuration is a named `ORKNUX_` variable, and a row in
  `DOCKERHUB.md`.** Spring would let any property be overridden by a name derived
  from its path, but a derived name is one nobody can look up and one that
  changes when a property moves - so every setting an operator would touch is
  written `${ORKNUX_SOMETHING:default}` in `application.yml`, with the reason
  beside it. `DOCKERHUB.md` claims to list all of them; adding one and not the
  other makes that claim false, and the claim is what an operator plans an
  upgrade from.
- **What arrives is published, not called.** `modules/connection` opens the
  sockets and knows nothing of triggers, so `SlackListener` publishes an
  `IncomingEvent` and `IncomingTriggerListener` in `app` decides what runs. The
  two `…Action` enums are matched by name, and a test holds them together.
- **Chat history is Spring AI's, not ours.** Messages live in
  `SPRING_AI_CHAT_MEMORY` keyed by a conversation id; `chat_session` holds only
  what that store does not. Do not add a messages table — a workflow run is
  meant to key a conversation the same way so its agents share one thread.
  Flyway creates that table and `initialize-schema` is `never`.
- **A model is only ever offered tools that will run.** `AgentTools` is the one
  place that knows every built-in, and what it offers depends on the agent's
  grants. A tool declared but not implemented is a model told it can do something
  it cannot, and it will believe you.
- **What a listener cannot deliver is not offered.** `DeliverableActions` names
  the events something actually publishes — Slack mentions, and nothing else yet.
  The enum holds the vocabulary the product intends; the picker offers what is
  wired, and saving a trigger on anything else is refused. A definition that is
  enabled, instanced and silent for ever is worse than one that could not be
  saved.
- **A tool is not a function.** Both are the workspace's JavaScript in the same
  sandbox, and both have their own table. What differs is the caller: an action
  node calls a function at a point the graph fixed, an agent calls a tool if it
  judges that it should. Do not fold them into one table with a flag.
- **A workspace's JavaScript is hostile input.** It runs through `ScriptRunner` and
  nowhere else: host access, class loading, IO, threads, processes and
  environment are all denied, limits are set, and everything crossing the
  boundary is JSON text. Widening any of that needs a reason and a test.
- **`orknux` is a door, not a network.** Everything the server does on a script's
  behalf - `log`, `http`, `slack` - hands JSON over and gets JSON back; the guest
  never holds a socket, a file or a host object, so there is nothing to reflect
  from. A new member goes through `PluginCapability` and the same door, never
  through a binding of its own. `http` reaches only where `ProxyRouter` allows,
  which is where an administrator decides what this server may talk to.
- **The two sandboxes share one source of helpers.** `HostHelpers` is the JavaScript
  both `ScriptRunner` and `PluginRunner` hand their guests. They were written out
  twice and drifted - the plugin side kept a `orknux.http` with no `post` and no
  parsed `json`, so the same call answered differently in the two places. Two
  copies of an API is two APIs; `PluginCapabilityTest` pins the parity.
- **A capability a function gets without asking has to be bounded by something
  other than judgement.** A plugin has an administrator to accept its grants and a
  function has nobody, so `forScripts` is a decision about what holds it in:
  reading a thread is bounded by the run's own workspace, and `NETWORK_REQUEST`
  by the installation's proxy rules. Write the reason beside the entry.
- **A built-in the server brings is a row on the Tools list, and a name in
  `agent.tools`.** `BuiltInTools.GRANTED` is the list; the form draws a row per
  entry and the round offers and withholds by the same names, so a tool added
  there is visible and switchable the same day. A new agent starts with all of
  them on and Always. Adding one is therefore two things: the name in that
  list, and a migration that gives every agent that predates it the name - or
  every existing agent has it switched off. V302 is the shape. The three
  booleans this rule used to name (`artifactAccess`, `finishAccess`,
  `pictureLinkAccess`) are views of the list now, kept so the API still
  answers them. A built-in that comes with a wider grant - the skill tools, the
  memories, `orknux_*`, the shells - is a row too, read-only, and is switched
  where that grant is.
- **A shed is how the loop is talked to, not a capability.** `finish_answer`
  ends the round because the work went somewhere the graph is not; `task_done`
  is the same idea. If a new tool is really the *caller* being addressed by the
  model inside its loop, it belongs in a `ToolShed` and not in `AgentTools`.
- **`stream: true` is a request, not a guarantee.** A provider may answer a
  streaming call with one ordinary body, and a reader looking for frames finds
  nothing and reports a silence the model never produced. Both client paths ask
  again without streaming where a stream carried nothing at all; a stub that
  answers plain JSON is therefore a valid provider, which is why the suite's
  stubs are left as they are.
- **Core does not name a plugin's tool.** The note on a drawn picture told a
  model to call `slack_uploadBinary`, which exists only where that plugin is
  loaded - so the instruction was wrong on every installation that had not
  loaded it, and core knew a plugin by name. Say what *kind* of tool to look for
  and let the agent read its own list. `StepPictureToolsTest` asserts the note
  names none.
- **What makes bytes answers a key; what has an address answers a link.** A
  picture a tool draws goes into the session's store under `picture.<id>`, and
  the key is what every tool that uploads a file takes. Handed a link instead, a
  model pastes it into a chat with no document to resolve an address against and
  the reader sees the construction. `save_artifact` is the exception, and the
  only one: an artifact *is* a thing at an address. Any link handed to a model is
  absolute - `WebProperties.baseUrl` - because a path has no host behind it once
  it has been copied somewhere else. The `url` fields the browser consumes stay
  relative; they are DTOs, not prose.
- **A query across a version boundary climbs down.** GraphQL fails a whole query
  over one field the far side has not heard of, so the marketplace client asks
  for the newest shape first and falls back a rung at a time - `Marketplace.LADDER`
  - and the browser's catalog query does the same. A field added on one side must
  be a new rung, never an edit to the bottom one.
- **What an action needs is derived, not stored.** `ActionAPI.inputsOf` reads
  the placeholders off the settings; a second copy in the database would drift.
- **A graph is checked by ports, not by kinds.** `GraphValidator` asks each node
  what it needs and gives, and compares that along the edges; a new node kind is
  a new branch in `portsOf` and nothing else. Only shapes that could never run
  are refused on save — the rest is advice, because a workflow is drawn before it
  is finished.
- **Catalogues, then instances.** Triggers, actions and conditions are defined
  once per workspace and used by pointing a node at one. What the thing *does*
  belongs to the catalogue entry, and a run keeps its own copy of which entry it
  used. A node stores the id, its own label, and what it passes — see below.
- **A node decides what it passes.** `workflow_node_mapping` holds one row per
  parameter, and that is what runs: `ActionNodeRunner` reads the mappings off the
  step, never off the action. The action's own `mappings` are a *seed* — read once
  in `WorkflowGraphAPI.mappingsFor` when a node is saved, and never again. This is
  why two nodes can call one function with different arguments, and why a plain
  value ("verbose") works where the definition only ever held an expression.
  Editing a node must not write to a trigger, action or condition.
- **A trigger is a catalogue entry, not a binding.** `workflow_trigger` names no
  workflow; a `workflow_node` of kind `TRIGGER` carries `trigger_id`, and that
  instance is the wiring. An arriving event matches definitions first and finds
  their instances second — `IncomingTriggerListener` shows both halves.
- **Saving is the draft; publishing is a copy.** The `workflow_node` and
  `workflow_edge` rows are the draft an editor writes to. `publishWorkflow` writes
  a snapshot of the runnable graph into `workflow_publication` — one row per
  publication since V180, the newest of which is what a trigger, a schedule or
  the API runs — `ExecutionPlanner` asks for
  `GraphVersion.DRAFT` only when the run was `MANUAL`. Anything that changes what
  a graph does has to be part of the snapshot, or it will be edited and not run;
  `WorkflowSnapshot` reads and writes it by hand rather than by reflection,
  because a shape in a database outlives the class it came from. The snapshot
  stops at the graph: of the action, agent or condition a node names it keeps the
  id, and the runner reads that row live — so editing a function changes what a
  published workflow does, without a republish. `PublishedDefinitionsTest` pins
  it; do not write a comment claiming a step froze a definition.
- **A version is written by the recorder, from every door.**
  `ComponentRevisionRecorder` is handed the state a save *displaced*, never the
  new one — the live row is already the newest version — and it is called from
  the browser's door and the `orknux_*` tools alike, for the reason the tracker's
  history learnt: covering only the door you can see leaves a hole exactly where
  nobody is watching. What counts as a version is `ComponentRevisionKind`'s
  answer and not the call site's. A new versioned kind assembles its own
  `RevisionSubject` rather than being reflected over, so a field somebody adds
  cannot be silently left out of the history.
- **Access is decided in roles, not in the provider's vocabulary.** A directory
  group or an OIDC claim is translated once, by `RoleResolver`, and everything
  past the front door — who administers, who sees which workspace — deals only in
  `Role`. Do not add a check that reads an authority string directly.
- **A notification is written by the desk, never by the screen that caused it.**
  `IssueNewsDesk` is where an event becomes news for somebody, so the bell and
  `orknux_news` cannot disagree about what happened. Reading marks read, so a
  reader that must not clear the count asks for `waiting`, not `unread`.
- **An issue is addressed by its number.** `#4` is per workspace and is what a
  URL, a tool call and a person all say; the row id is an implementation detail
  that should not reach an API or a link.
- **A value put in the session store says what it is.** Every writer passes a
  `StoredKind` - its content type, and whether the value is base64 bytes -
  and every reader asks `kindOf` before it guesses. Guessing from the value is
  how a PDF reached Slack as a text file of base64. Text types hold the text
  itself; everything else holds base64.
- **A workspace copy decides every field.** `WorkspaceDuplicator` copies
  connections, model providers, models and MCP servers field by field, and
  leaves their credentials behind. `WorkspaceDuplicateTest` lists every field
  of those entities as copied or left on purpose, so a field added to one fails
  a test until somebody decides whether a copy carries it.
- **There is one attachment store.** Chat attachments and issue attachments share
  `AttachmentStore`, `InstallationSettings` and the list of what may be served
  inline. A second copy of the inline rule is how one of them ends up serving
  something the other refuses.
- **A tool the MCP endpoint offers is a tool an agent can be given.**
  `OrknuxTools` is the one place that knows the `orknux_*` surface, and the scope
  it is called with is what decides whether writing is offered. Adding a tool in
  one place and not the other is two products.
- **What arrives at a session goes through its inbox.** `SessionInbox` is
  where an answer an agent asked for, or a reminder it set, is left; a turn
  reads it between rounds and at its start, and `SessionEventDue` is what wakes
  a session that is not running - `SessionWake` for workflow steps and tasks,
  `ChatWake` for chats. Something new that finishes after the turn that
  started it posts there too, rather than growing a wake-up of its own. The
  briefing's "How you run" is the other half: the model is told there is no
  later unless it makes one, and the inbox is the backup when it forgets.
- Comments say why, not what. KDoc on public types and anything with a rule
  behind it.

## Releasing

`CHANGELOG.md` is written **before** the tag, not after it. A changelog
assembled from commit subjects once the release is out reads like a changelog
and is nobody's account of what happened: the person who knew why a change
mattered has moved on, and what survives is a list of what was touched.

The order is: land the work, write the entry, bump the version, tag. The tag is
what CI turns into images, so anything after it is in the next release whether
or not it belongs there.

Concretely, for `X.Y.Z`:

1. Move the **Unreleased** heading in `CHANGELOG.md` to `## X.Y.Z`.
2. Bump `<version>` in the root `pom.xml` and the `<parent>` version in
   `app/pom.xml`, `modules/connection/pom.xml` and `modules/execution/pom.xml`.
   The application reports `@project.version@` on the monitoring screen, so a
   version left behind is a version an operator is shown.
3. Bump `version` in `orknux-ui/package.json` and move the submodule pin. The two
   halves are released together under one number.
4. Move the pin in `deploy/compose.yaml` - `ORKNUX_SERVER_TAG` defaults to the
   minor (`0.9`), and moves only when the minor does - and every version
   quoted in `deploy/README.md`, `deploy/kubernetes/orknux.yaml` and
   `deploy/kubernetes/README.md`. `grep -rn "X.Y.Z-1"` is the check: a version
   left behind is one an operator is handed.
5. Tag `vX.Y.Z` and push it.

CI does the rest: `.github/workflows/ci.yml` builds, runs the suite, runs
`scripts/verify-image.sh` against the built image, and only then publishes.
`latest` follows `main`; a `v*` tag also publishes `X.Y.Z` and `X.Y`; every build
is tagged `sha-<commit>`, which is the only tag that never moves.

**The manual's pictures are retaken at a minor release, in one command.**
`scripts/screenshots.ps1`, and from PowerShell — under Git Bash, MSYS rewrites
the paths it hands out and the damage turns up inside an image rather than as an
error. Bump `orknux-ui/package.json` before running it: the footer prints that
version on every signed-in screen, and the script refuses to start while it
disagrees with the pom.

It photographs an installation it builds, never this machine's. `seed-demo.mjs`
deletes and rebuilds the workspace it wants, so aimed at a development database
it is a documentation script editing somebody's work. What it aims at instead is
the jar the reactor has just produced, run on the host against a SQLite file in
a temporary directory, beside the containers in `scripts/screens-compose.yaml`
that could not be a jar: a directory, so alice signs in through one and the users
page has the External row the manual's paragraph about the two kinds of account
needs, and a Temporal with a Postgres of its own, so Monitoring draws the row
that chapter describes. Five minutes end to end, or seven on the first run, when
the interface container is still fetching its dependencies and a browser - it
was forty when the installation being photographed was `Dockerfile.one` built
inside Docker, and thirty-five of those were the build.

**This repository publishes two images, and each is the whole product.**
`orknux-server` from `Dockerfile`, and the all-in-one `orknux-one` from
`Dockerfile.one` - the same jar with a SQLite file beside it, verified by
`scripts/verify-one-image.sh` and described by `DOCKERHUB-ONE.md`. Since #585 the
server serves the interface from its own jar (`InterfaceResources`, packaged by
`-Pwith-ui`), so there is no nginx and no `orknux-ui` image any more; both
Dockerfiles build the bundle from the `orknux-ui` submodule, and every CI job that
builds an image checks out with `submodules: true`. Everything the
image invents on a first start - the encryption key, the database, the
administrator - is in `docker/one/entrypoint.sh`, and the key is the part to be
careful with: generating a second one on a later start makes every stored
credential unreadable without failing anything.

**`DOCKERHUB.md` and `DOCKERHUB-ONE.md` are published by CI, and have a size
limit.** They are the Docker Hub repository descriptions, one per image, and the
publish jobs push them after the images go up - last, and after the push, so a
description that fails to update cannot stop a release. It used to be pasted into a web form by hand, which is how it came to
be eleven variables out of date while being correct in git; an operator planning
an upgrade reads the one on Docker Hub, so that gap was the whole of the problem.

Two things follow from it. **Docker Hub takes 25,000 bytes and the action
truncates silently past that**, so the build fails when the file is over - the
check is in the `build` job, before the suite, and it says how many bytes to
lose. Shorten it or move a section into `README.md` and link, but do not let it
be cut, because the end of the file is where the least-known settings are. And
**the token needs read, write and delete scope**: updating a description is not
a push, and a token scoped only to push works perfectly for the image and
answers 401 for this.

It claims to list every environment variable the server reads;
`grep -oE 'ORKNUX_[A-Z0-9_]+' app/src/main/resources/application.yml` against it
is how that claim gets checked.

Three headings, and no others: **Added** for what is new, **Changed** for what
an existing installation will do differently - the section people actually need
- and **Fixed** for what was wrong. Anything under Changed that requires an
action on upgrade says so in the entry itself; somebody reading it should never
have to go and find out.

One file, in the server repository, covering both halves. They are released
together under one version, and a reader holding two changelogs side by side to
work out what a release contains is a reader we have failed.

## Tests

Every API change comes with `GraphQlTester` coverage in the matching package,
driving the real modules — there is no stand-in for a module now that they are in
the same process. Watch out for:

- `entityList(String)` fails on object arrays — select a scalar path, or use
  `.get()` and assert with AssertJ.
- `containsExactly` works on `entityList`; `isEqualTo(list)` and `containsOnly`
  do not.
- Tests must not depend on the network. A probe test points at a `.invalid`
  host, which can never resolve.
- Surefire needs `useManifestOnlyJar=false` here: without spring-boot-starter-parent
  the classpath goes through a manifest-only jar and Spring never finds
  `application.yml`. The suite also runs with `orknux.temporal.enabled=false`,
  so a run happens on the calling thread, `orknux.slack.enabled=false`, so no
  test opens a websocket to Slack, `orknux.model.check.enabled=false` and
  `orknux.connection.check.enabled=false`, so no sweep calls anything on a timer
  — `ModelAPITest` builds the monitor and calls `sweep()` itself — and
  `db-scheduler.enabled=false`, so no clock fires while a
  suite is running — `TriggerSchedulerTest` calls the tick itself. It also sets a
  fixed `orknux.security.secret-key`, because the suite stores credentials and
  they are encrypted with it; a real key has no business in a build file, and the
  test database is thrown away anyway.
- **A test that waits must not really wait.** A parked step is waited out on the
  thread by the inline engine, so keep test waits to a second or two; the
  Temporal path is the one to test a long wait on, where
  `TestWorkflowEnvironment` skips the timer and an hour costs nothing
  (`ExecutionWorkflowTest`).
- A stale `target/test-classes/application.yml` shadows the real config; if the
  datasource suddenly cannot be determined, run `clean`.
- **A test the suite leaves out is tagged `slow`, and says why it is skipped.**
  Surefire excludes that tag by default - `orknux.test.excluded-groups` in
  `app/pom.xml` - and `./mvnw test -Dorknux.test.excluded-groups=` runs them.
  There is one, `ShellCodingTaskTest`: it builds a JDK image, brings up three
  containers and asks a real model to write a Spring application, push it to a
  gitea in Docker and have a clone of it answer an HTTP request. Two rules come
  with the tag. A tagged test still refuses to run without what it needs -
  `@EnabledIfEnvironmentVariable` on `ORKNUX_TEST_MODEL_KEY` there, with a
  `disabledReason` a surefire report prints - so clearing the exclusion on a
  machine that has no key skips rather than fails. And it must not be made to
  pass by standing something in for its subject: the point of that test is
  whether a model can do the work over this product's three shell tools, and a
  stub returning the commands a working agent would have run proves only that
  the commands work.
- **The suite has its own database.** `TestDatabase` is a JUnit
  `LauncherSessionListener`, found through `META-INF/services`, that starts a
  Postgres container and points `spring.datasource.*` at it before the first
  Spring context is built. Registered there rather than on each test class, so a
  test added later cannot forget to opt in and quietly wipe development data —
  which is exactly what `deleteAll()` in the fixtures would do.
  - Do not try to arrange this with `context.initializer.classes`: Spring Boot 4
    dropped `DelegatingApplicationContextInitializer`, so the property is read by
    nothing, no container starts, and the suite silently runs against the
    development database while looking like it worked.
  - Testcontainers 2 is what Boot's BOM manages, and the module is
    `testcontainers-postgresql` — not the `postgresql` of every 1.x example.
    `junit-platform-launcher` needs declaring too; surefire only supplies it at
    runtime, so compiling the listener against it fails without.
  - A canary row in the development database, checked after a run, is the only
    thing that actually proves the isolation. Assume nothing here.
