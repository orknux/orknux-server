# Running more than one server

An audit for #597: every place the server assumed it was the only process on
its database, what goes wrong when it is not, and what was done about it. Read
against the code at 0.9.9.11; the status column says what 0.9.9.12 changes.

**The short answer.** Several replicas on one Postgres work when Temporal runs
the workflows and tasks, the attachments directory is a volume every replica
mounts, and the load balancer keeps one browser on one replica (cookie
affinity). SQLite is one process by nature and stays one. The inline engine is
one process too, and since 0.9.9.12 a second server on the inline engine refuses
to start rather than quietly doing every task twice.

## How "exactly one" is decided

One lease in the database, `orknux-leader`, held by one replica at a time and
renewed on a timer. It is a ShedLock lock (`shedlock` table) taken with
`JdbcTemplateLockProvider` and kept alive with ShedLock's own `extend`, so the
lock protocol is the library's and not ours. `ClusterLeader` in
`modules/connection` is the wrapper: it takes the lock on start, extends it
every third of the lease, lets go on a graceful stop, and answers `leads()`.
When the holder dies without letting go, nobody extends the row, the lease runs
out, and the next replica to try takes it - so whatever was gated on it moves
to that replica within one lease.

The lease length is the installation's (`ORKNUX_CLUSTER_LEASE_SECONDS`, thirty
unless set) and an Admin Settings field overrides it. Shorter fails over faster
and writes the row oftener; it has to stay comfortably longer than the longest
pause a replica can take (a full GC, a stalled disk), or two replicas can both
believe they lead for the length of that pause.

`orknux.cluster.lease-enabled=false` turns the lease off and makes every process
its own leader. The suite sets it, because one JVM there holds several Spring
contexts on one database and they are not replicas of each other; tests that
are about the lease build their own.

## Audit

### Held in memory per process

| What | Where | With 2+ replicas | Status |
|---|---|---|---|
| Sign-in sessions | `SessionStoreConfig` (`@EnableJdbcHttpSession`, V71) | Already rows in `SPRING_SESSION`; any replica reads them. The cleanup cron runs on every replica and is an idempotent delete. | Safe, nothing to do |
| Chat answer stream (SSE) | `ChatStreamAPI.stream` | The answer is streamed by the replica that took the POST; that is one request, so it cannot move. | Safe |
| Chat Stop button | `ChatGenerations` | The interrupt is a second POST; on another replica it finds no turn to hang up, and the answer runs to its end. | Needs cookie affinity - documented, not fixed |
| Chat woken by an answer or a reminder | `ChatWake` (`waking`, `generations.answering`) | "Not while a turn is running" is asked of this JVM only, so a wake on replica B can start a second turn beside a person's turn on A. | Reminders: only the leader announces them now. Answers posted by an agent on another replica: not fixed, listed below |
| Task and session live view | `SessionTail`, `TaskStreamAPI` | Polls `llm_session_event` every two seconds as a backstop to the in-JVM signal, so another replica's writes arrive within two seconds. | Safe (written for this, see the class comment) |
| `orknux_news` waiting | `IssueNewsDesk` bells, `NewsTools` | The bell rings in-JVM only, and the waiter looks again every five seconds anyway. | Safe, up to five seconds late |
| Session inbox wake-ups | `SessionDueSweeper` (`lookedUpTo`) | Every replica announced every due reminder, so every reminder woke its session once per replica. | **Fixed**: leader only; see below |
| Sign-in throttle | `SignInThrottle` | Counts per replica, so N replicas allow N times the tries. | Not fixed, listed below |
| Model throttle | `ModelThrottle` | A provider's concurrency and rate limits are kept per replica. | Not fixed, listed below |
| Workspace copy progress | `WorkspaceCopyProgress` | The page polls a key the copying replica holds; another replica answers "nothing". | Needs cookie affinity - documented |
| Step Stop reaching a running call | `StepInterrupts` | Reaches steps in this JVM only; already said in its comment. The stop flag on the run is in the database, so the run stops after the step either way. | Documented |
| Slack dedup, bot users, directory, model clients, tokens, icons | `SlackListener.delivered`, `SlackBotUsers`, `SlackDirectoryCache`, `ModelClients`, `ModelProviderProbe`, `MarketplaceIcons` | Caches of something re-readable; a second copy costs a call, not correctness. `delivered` matters only where the socket is, which is one replica now. | Safe |
| Log levels | `LogLevels` | Already follows the rows on every replica (#591). Must run on every replica, so not gated. | Safe |

### Listeners that must exist once

| What | With 2+ replicas | Status |
|---|---|---|
| Slack Socket Mode (`SlackListener`) | Each replica opened a socket per connection. Slack spreads events across a connection's sockets rather than copying them, so the usual case was one delivery - but a redelivery after a missed acknowledgement can land on the other replica, whose dedup map has never seen it, and the Connections page answered for whichever replica it asked. | **Fixed**: only the leader opens sockets; losing the lease closes them; another replica answers `stateOf` as "listening on another server" |
| Plugin daemons | There are none in this repository: a plugin runs per call through `PluginRunner`. | Nothing to do |
| db-scheduler (`TriggerSchedulerConfig`) | Cluster-aware by design: one replica picks each execution off `scheduled_tasks`. | Safe |

### Timers

Each of these ran on every replica. All of them are now asked on each pass
whether this replica leads, and skip the pass when it does not; a test can still
call `sweep()` directly.

| Timer | What doubling did | Status |
|---|---|---|
| `ModelProviderMonitor`, `ConnectionMonitor`, `McpServerMonitor` | N outbound checks per interval against every provider, connection and MCP server, and N writes of the same status. | **Fixed** |
| `ShellSessionSweeper` | N SSH logins per host per pass, and two replicas expiring the same session at once. | **Fixed** |
| `TaskSweeper` | Two replicas handing the same stranded task over at once. | **Fixed** |
| `ExecutionSweeper`, `RevisionSweeper`, `ScratchpadSweeper` | Concurrent deletes of the same rows; harmless but wasteful. | **Fixed** |
| `SessionDueSweeper` | Each reminder announced once per replica. A follower keeps its mark one lease behind now, so a replica that takes over re-reads the gap rather than skipping it. | **Fixed** |
| `ParkedRunSweeper` (inline engine only) | Another replica's long model call looks stranded and is resumed a second time. | Inline engine is one replica only - see below |
| The session cleanup cron, `LogLevels` follow, `ReaderWatch`, `SessionTail` pump | Per-replica by nature. | Not gated, on purpose |

### Files on local disk

| What | Where | Status |
|---|---|---|
| Chat and issue attachments | `AttachmentStore`, `orknux.attachments.location` | On the replica's disk. Several replicas need one volume they all mount read-write (NFS, EFS, a `ReadWriteMany` claim). Object storage is the real answer and is not built - `AttachmentStorage` has one value. Listed below. |
| Artifacts | `workspace_artifact` | In the database. Safe. |
| Scratchpads | `session_scratchpad` | In the database. Safe. |
| Release jars | `server_release` | In the database; each container's launcher writes its own copy to its own `ORKNUX_RELEASE_DIR`. Safe. |
| Plugin downloads | `Marketplace` | Written to a temporary file and kept in the database. Safe. |

### Start-up

| What | With 2+ replicas | Status |
|---|---|---|
| Flyway | Takes a Postgres advisory lock; the second replica waits for the first. | Safe |
| `BootstrapAdmin` | Two replicas starting on an empty database at once can both try to create the administrator; the unique name refuses one, which fails to start and is restarted, and finds the account there. | Documented |
| `SecretMigration` | Two replicas may seal the same plaintext row; either ciphertext is valid. | Safe |
| `InlineTaskEngine.start` | Picks up every task at RUNNING - including the ones another replica is running. | Inline engine is one replica only |

### Self-update (#584)

The release is a row, the schema floor is a row, and every replica follows the
chosen release by polling (`ServerRestart.follow`). So an activation reaches
every replica, and a replica that cannot run the release is refused by its own
launcher on the same floor. What it does not do is roll: every replica sees the
change within one follow interval and they restart together, which is a short
outage rather than a lost request. **Not fixed**, listed below.

### The two engines

Temporal holds a run's history and its workers are meant to be many, so a
Temporal installation scales out as it is. The inline engine carries a run or a
task on a thread of the process that started it, revives everything at RUNNING
when a process starts, and sweeps for runs nobody seems to be carrying - all
three of which take another replica's live work for abandoned work. **Fixed by
refusing**: on the inline engine the lease is required at start-up. A server
that cannot take it waits one lease (long enough for a crashed predecessor's to
run out) and then refuses to start, saying that another server holds it and
that more than one needs Temporal. The Doctor check "Replicas" says which
replica leads.

## What is not done, and why

- **Attachments on shared storage.** A shared volume works today and is what
  the documentation asks for. Object storage needs a second `AttachmentStorage`,
  an SDK, credentials and a migration of existing files - a feature, not a fix.
- **The Kubernetes example stays at one replica.** Its data claim is
  `ReadWriteOnce` and it runs with the inline engine's defaults; raising it means
  a `ReadWriteMany` class and Temporal, neither of which the example can assume.
  The README says what to change.
- **Cookie affinity is required, not optional.** The chat Stop button and the
  workspace copy progress are held by the replica doing the work. Moving them
  to the database is possible and was not worth doing blind; affinity on the
  session cookie covers both.
- **A chat woken by an answer on another replica** can start a turn beside a
  running one. Needs the "a turn is running" fact in the database.
- **Sign-in and model throttles are per replica.** N replicas allow N times
  the sign-in attempts and N times a provider's concurrency.
- **Rolling restart on activation.** Replicas restart together. Staggering them
  wants a second lock and a readiness signal from the restarted replica.
- **A two-replica test environment running the suites.** The lease, the gating,
  the Slack hand-over and the refusal are tested in the suite on both databases
  with two lease holders in one JVM; nothing yet drives the end-to-end and
  browser suites through a load balancer at two replicas.
