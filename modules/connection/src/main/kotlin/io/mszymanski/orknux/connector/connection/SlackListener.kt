package io.mszymanski.orknux.connector.connection

import io.mszymanski.orknux.connector.cluster.ClusterLeader
import com.google.gson.JsonParser
import com.slack.api.bolt.App
import com.slack.api.model.event.AppMentionEvent
import com.slack.api.model.event.MessageBotEvent
import com.slack.api.model.event.MessageEvent
import com.slack.api.model.event.MessageFileShareEvent
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Listens to Slack over Socket Mode, one websocket per workspace connection that
 * carries an app-level token.
 *
 * Socket Mode dials out, so nothing here needs a public URL, an inbound
 * firewall rule or a request signature — which is what makes a self-hosted
 * orknux able to receive Slack events at all. Dialling out is still an outbound
 * call, so it goes through the proxy rules like any other; [SlackClients] is
 * how, and why it takes two lines here rather than one.
 *
 * What arrives is published as an [IncomingEvent]; matching it to a trigger and
 * starting a workflow belongs to whoever owns those, not to this module.
 *
 * **Three things are listened for**: a mention, a message in any channel the bot
 * is a member of, and a thread reply, which is a message with a thread on it.
 * The last two are a different order of traffic from the first — a mention is
 * addressed to us and a message is everything anybody types — and they need
 * `channels:history` on the bot token, plus `groups:`, `im:` and `mpim:` for the
 * other three kinds of conversation, plus the matching `message.*` subscriptions
 * on the Slack app. A token without them opens the socket perfectly and hears
 * nothing; `SlackBotUsers` is what says so in one line.
 *
 * Connections change while the process runs — a workspace pastes a token, another
 * disconnects — so the set of sockets is reconciled on a timer rather than only
 * at startup. A connection whose credentials changed is closed and reopened,
 * since a session outlives the token it was opened with.
 *
 * **Two more reasons to reopen one, since #592.** Somebody pressed Reconnect -
 * on this replica or another, which is why the press is a recorded
 * [SlackReconnectRequests] generation rather than a call - or the socket went
 * quiet for longer than `orknux.slack.quiet-period` and then did not answer a
 * ping. Either way the socket used to stay "open" until a restart, because
 * nothing short of a changed token ever closed it.
 */
@Component
@ConditionalOnProperty(prefix = "orknux.slack", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class SlackListener(
    private val workspaceConnections: WorkspaceConnectionRepository,
    private val events: ApplicationEventPublisher,
    private val properties: SlackProperties,
    /** Where the two tokens come from: the connection's own copies, or workspace secrets. */
    private val credentials: ConnectionCredentials,
    private val slackClients: SlackClients,
    /** Who each connection posts as, which is how a reply to one of ours is known. */
    private val botUsers: SlackBotUsers,
    /** What dials Slack; a stand-in in a test, which has no network. */
    private val sockets: SlackSockets = SocketModeSockets(slackClients),
    /** The Reconnect presses every replica reads; see [SlackReconnectRequests]. */
    private val reconnects: SlackReconnectRequests = InMemorySlackReconnectRequests(),
    /**
     * Which replica holds the sockets. Issue #597: every replica used to open
     * its own, and a redelivery Slack sent to the second one was a second run,
     * because the record of what was delivered is this process's. Alone where
     * nothing says otherwise.
     */
    private val leader: ClusterLeader = ClusterLeader.alone(),
) {

    /** Open sockets by workspace connection id. */
    private val sessions = ConcurrentHashMap<Long, SlackSession>()

    /** The credentials that would not open, so they are not tried on every pass. */
    private val failures = ConcurrentHashMap<Long, FailedAttempt>()

    /**
     * When each connection last delivered an event, kept apart from its session
     * so a reconnect does not make it look as though nothing ever arrived.
     */
    private val lastEvents = ConcurrentHashMap<Long, Instant>()

    /** Why each connection last failed to open, kept after it recovers. */
    private val lastFailures = ConcurrentHashMap<Long, Failure>()

    /**
     * One pass or one press at a time. A press arriving mid-pass would
     * otherwise close a socket the pass is about to judge, and open a second
     * beside the one the pass opens.
     */
    private val lock = Any()

    private val reconciler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "slack-listener").apply { isDaemon = true }
    }

    /**
     * Slack wants an acknowledgement within three seconds, and what a mention
     * sets off is a workflow run, so the socket thread hands the event on and
     * goes back to reading.
     */
    private val dispatcher = Executors.newVirtualThreadPerTaskExecutor()

    /**
     * What has already been delivered, so Slack sending it again does not run
     * it again.
     *
     * **Slack redelivers, and it is right to.** An event it does not see
     * acknowledged within three seconds is sent again, up to three times, and
     * the same is true of everything that arrived while a process was down or
     * failing: they queue and land when it comes back. Without a guard each
     * copy is another workflow run - another answer posted in the thread,
     * another turn billed - and the thread reads as a bot repeating itself.
     * Three runs off one message were sitting in this installation's history
     * before anybody went looking.
     *
     * Keyed by the connection, the action and Slack's own `ts`, which names
     * the message. The action is in the key on purpose: one message is
     * legitimately a MESSAGE and a REPLY, and an `@` is legitimately an
     * `app_mention` and a `message` - those are different events about the
     * same words, and a trigger is entitled to each of them.
     *
     * Held in memory rather than in the database. What this must survive is a
     * retry seconds later on a process that is running; a restart clears it,
     * and a restart is also when Slack's queued redeliveries arrive - but a
     * table written on every Slack message to guard a case that ends with one
     * duplicate answer is the more expensive mistake.
     */
    private val delivered = ConcurrentHashMap<String, Long>()

    /*
     * Losing the lease closes every socket at once, rather than at the next
     * pass up to half a minute later, while another replica is already opening
     * its own. Taking it opens them now rather than then. Here rather than in
     * [start], so a listener built by hand hears it too.
     */
    init {
        leader.onChange { leading ->
            if (leading) {
                runCatching { reconciler.execute { runCatching(::reconcile) } }
            } else {
                synchronized(lock) { sessions.keys.toList().forEach(::close) }
            }
        }
    }

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        reconciler.scheduleWithFixedDelay(
            { runCatching(::reconcile).onFailure { log.warn("Could not reconcile Slack listeners", it) } },
            0,
            properties.reconcileSeconds,
            TimeUnit.SECONDS,
        )
    }

    /**
     * Brings the open sockets in line with what the connections now say.
     *
     * Visible for the sake of a caller that has just changed a connection and
     * would rather not wait for the timer.
     */
    fun reconcile() = synchronized(lock) {
        val slack = workspaceConnections.findByType(ConnectionType.SLACK)

        /*
         * What is remembered about a connection goes when the connection does.
         * Both maps are kept past a session on purpose - the page still says
         * when the last event came and why the last attempt failed - and
         * nothing else ever took an entry out, so every Slack connection ever
         * deleted stayed in them. Issue #616. By existence rather than by
         * listening: one that has only lost its app-level token is still there
         * to show its history.
         */
        val present = slack.mapNotNull { it.id }.toSet()
        lastEvents.keys.retainAll(present)
        lastFailures.keys.retainAll(present)

        if (!leader.leads()) {
            // Another replica holds the sockets; whatever this one still has is
            // a second listener on the same app, and goes.
            sessions.keys.toList().forEach(::close)
            return@synchronized
        }
        /*
         * The app-level token is what decides this, not the type: a Slack
         * connection given one listens, one left without it only sends.
         *
         * Resolved rather than read off the row, because either token may be a
         * workspace secret now. It is also what the fingerprint is taken over,
         * so rotating the variable closes the session that was opened with the
         * old value - fingerprinting the columns instead would leave a socket
         * running on a token nobody uses any more, silently, until a restart.
         */
        val wanted = slack
            .mapNotNull { connection -> listening(connection)?.let { requireNotNull(connection.id) to it } }
            .toMap()

        /*
         * Read once a pass. A database that will not answer costs the presses
         * made meanwhile and nothing else - every socket stays as it is.
         */
        val generations = runCatching { reconnects.generations() }
            .onFailure { log.warn("Could not read the Slack reconnect requests: {}", it.message) }
            .getOrDefault(emptyMap())

        for ((id, session) in sessions) {
            val generation = generations[id] ?: 0
            when {
                wanted[id]?.fingerprint != session.fingerprint -> close(id)
                generation > session.generation -> {
                    log.info("Reconnecting Slack on connection {}: somebody asked to", id)
                    close(id)
                }
                silent(id, session) -> close(id)
            }
        }
        failures.keys.removeIf { it !in wanted }
        for ((id, connection) in wanted) {
            if (sessions.containsKey(id)) continue
            val failure = failures[id]
            // A press made after the failure is the attempt the wait holds back.
            val asked = failure != null && (generations[id] ?: 0) > failure.generation
            if (connection.waitingAfterFailure(failure) && !asked) continue
            open(id, connection, generations[id] ?: 0)
        }
    }

    /**
     * Closes this connection's socket and opens it again at once, here and on
     * every other replica, and answers what came of it here.
     *
     * The press is recorded first, so a replica that is not this one honours it
     * on its next pass whatever happens below. The wait after a failure is
     * cleared, because somebody pressing Reconnect is asking for exactly the
     * attempt that wait holds back.
     */
    fun reconnect(connectionId: Long): SlackSocketState {
        val generation = reconnects.request(connectionId)
        // Recorded above for the replica that holds the sockets, which honours
        // it on its next pass; opening one here would be the second listener.
        if (!leader.leads()) return stateOf(connectionId)
        synchronized(lock) {
            close(connectionId)
            failures.remove(connectionId)
            val connection = workspaceConnections.findById(connectionId).orElse(null)
                ?.takeIf { it.type == ConnectionType.SLACK }
                ?.let(::listening)
            if (connection != null) {
                log.info("Reconnecting Slack on connection {}: somebody asked to", connection.name)
                open(connectionId, connection, generation)
            }
        }
        return stateOf(connectionId)
    }

    /**
     * How this replica's socket for one connection is doing.
     *
     * This replica's, and only that: another holds a socket of its own and
     * would answer for it. On one server - which is nearly every installation -
     * that is the whole answer.
     */
    fun stateOf(connectionId: Long): SlackSocketState {
        val session = sessions[connectionId]
        val failure = lastFailures[connectionId]
        val status = when {
            session != null -> SlackSocketStatus.CONNECTED
            !leader.leads() ->
                if (workspaceConnections.findById(connectionId).orElse(null)?.let(::listening) != null) {
                    SlackSocketStatus.ELSEWHERE
                } else {
                    SlackSocketStatus.NOT_LISTENING
                }
            failures.containsKey(connectionId) -> SlackSocketStatus.FAILED
            workspaceConnections.findById(connectionId).orElse(null)?.let(::listening) != null ->
                SlackSocketStatus.CONNECTING
            else -> SlackSocketStatus.NOT_LISTENING
        }
        val appConnections = session?.appConnections?.get()
        return SlackSocketState(
            status = status,
            connectedSince = session?.openedAt,
            lastEventAt = lastEvents[connectionId],
            lastFailure = failure?.reason,
            lastFailureAt = failure?.at,
            appConnections = appConnections,
            sharedWithOthers = session != null && appConnections != null &&
                appConnections > ownSessionsOf(session),
        )
    }

    /**
     * Whether a socket has gone quiet for longer than it should and does not
     * answer when asked.
     *
     * Silence alone is not death: a workspace nobody types in at night sends
     * nothing for hours. So a quiet socket is pinged, and only one that does
     * not answer is reopened - an answer counts as hearing from it, and the
     * question is not asked again until it has been quiet as long once more.
     *
     * A quiet period of zero or less turns this off.
     */
    private fun silent(id: Long, session: SlackSession): Boolean {
        val quiet = properties.quietPeriod
        if (quiet.isZero || quiet.isNegative) return false
        val now = Instant.now()
        val since = session.lastHeard.get()
        if (Duration.between(since, now) <= quiet) return false
        if (runCatching { session.socket.alive() }.getOrDefault(false)) {
            session.lastHeard.set(now)
            return false
        }
        log.warn(
            "Reconnecting Slack on connection {}: nothing arrived since {} and it did not answer a ping",
            id,
            since,
        )
        return true
    }

    /**
     * One frame off a socket: a sign of life, and - where it is Slack's `hello` -
     * how many connections Slack is holding for this app.
     *
     * **Slack splits one app's events between all its connections.** Every
     * Socket Mode connection opened for an app, by whichever app-level token and
     * from whichever machine, is handed a share of that app's events and the
     * others never see them. So a second installation listening with the same
     * app - an old development server left running is how it was found - takes
     * a trigger's mentions at random, and from here that is a trigger that
     * works sometimes. `hello` says `num_connections`; more of them than this
     * installation holds is somebody else listening.
     */
    private fun heard(connectionId: Long, session: SlackSession, frame: String) {
        session.lastHeard.set(Instant.now())
        if (!frame.contains("\"hello\"")) return
        val hello = runCatching { JsonParser.parseString(frame).asJsonObject }.getOrNull() ?: return
        if (hello.get("type")?.takeIf { it.isJsonPrimitive }?.asString != "hello") return
        val count = hello.get("num_connections")?.takeIf { it.isJsonPrimitive }?.asInt ?: return
        hello.getAsJsonObject("connection_info")?.get("app_id")?.takeIf { it.isJsonPrimitive }?.asString
            ?.let(session.appId::set)

        val before = session.appConnections.getAndSet(count)
        val own = ownSessionsOf(session)
        // Once per change, not once per hello: Slack says hello on every
        // refresh, and a warning repeated every few hours is one nobody reads.
        if (before != count && count > own) {
            log.warn(
                "Slack splits this app's events between {} connections on connection {}, and this server holds {}: " +
                    "another server is listening with this app, and receives a share of its events",
                count,
                connectionId,
                own,
            )
        }
    }

    /**
     * How many of the sessions this server holds belong to one Slack app.
     *
     * Two connections in two workspaces may well be the same app; a session
     * that has not said which app it is counts for itself alone. The session
     * asked about counts whether or not it is in [sessions] yet, since the
     * first `hello` arrives while it is still opening.
     *
     * A server's own, and only that: an installation run as two replicas holds
     * two connections per app, and each replica reads the other as somebody
     * else - which, for where Slack sends an event, it is.
     */
    private fun ownSessionsOf(session: SlackSession): Int {
        val appId = session.appId.get() ?: return 1
        return 1 + sessions.values.count { it !== session && it.appId.get() == appId }
    }

    /**
     * One connection with both its tokens in hand, or null when it is not one
     * that listens - no bot token, no app-level token, or a reference to a
     * workspace secret that has gone or was never filled in.
     */
    private fun listening(connection: WorkspaceConnection): Listening? {
        val bot = credentials.secretOf(connection).credential ?: return null
        val app = credentials.appTokenOf(connection).credential ?: return null
        return Listening(connection, bot, app)
    }

    private fun open(id: Long, connection: Listening, generation: Long) {
        val workspaceId = connection.workspaceId
        // Made before the socket, so a `hello` arriving while it opens has
        // somewhere to land.
        val session = SlackSession(connection.fingerprint, generation)
        try {
            session.socket = sockets.open(
                SlackSocketRequest(
                    connectionId = id,
                    name = connection.name,
                    botToken = connection.botToken,
                    appToken = connection.appToken,
                    register = { app -> register(app, id, workspaceId) },
                    heard = { frame -> heard(id, session, frame) },
                ),
            )
            sessions[id] = session
            failures.remove(id)
            log.info("Listening to Slack on connection {} (workspace {})", connection.name, workspaceId)
        } catch (failure: Exception) {
            // A bad token, or Slack being unreachable. Neither is a failure of
            // the application, and neither is worth asking about every 30
            // seconds, so it waits - until the credentials change, or somebody
            // presses Reconnect.
            val now = Instant.now()
            failures[id] = FailedAttempt(connection.fingerprint, now, generation)
            lastFailures[id] = Failure(failure.message ?: failure.javaClass.simpleName, now)
            log.warn(
                "Could not listen to Slack on connection {} (workspace {}): {}",
                connection.name,
                workspaceId,
                failure.message,
            )
        }
    }

    /** What a session listens for, registered on the app it is built on. */
    private fun register(app: App, id: Long, workspaceId: Long) {
        app.event(AppMentionEvent::class.java) { payload, context ->
            publish(id, workspaceId, payload.event, payload.teamId)
            context.ack()
        }

        /*
         * Everything anyone types in a channel this bot can read.
         *
         * A mention is addressed to us and a message is not, which is the
         * difference worth keeping in mind when reading the volume: this
         * arrives once per message in every channel the bot is a member of,
         * for as long as the token carries `channels:history`. What keeps
         * that affordable is that the work is a repository query against the
         * trigger catalogue and nothing more until something matches.
         */
        app.event(MessageEvent::class.java) { payload, context ->
            receive(id, workspaceId, payload.event, payload.teamId)
            context.ack()
        }

        /*
         * A message that carries a file, which Slack delivers as its own
         * kind of event.
         *
         * Nothing was registered for it, so Bolt answered every upload with
         * `no handler found` and the message was dropped on the floor -
         * with its text, its thread and its file. What that looked like
         * from a Slack channel is somebody attaching a PDF, asking the bot
         * about it, and the bot replying that it has no PDF: the mention
         * arrived, the upload never did, and the agent was telling the
         * truth about what it had been given.
         *
         * The same path as an ordinary message, because that is what it is
         * - `message` with a `file_share` subtype - and the same loop
         * guard applies to it.
         */
        app.event(MessageFileShareEvent::class.java) { payload, context ->
            receive(id, workspaceId, payload.event, payload.teamId)
            context.ack()
        }

        /*
         * A bot's message, acknowledged and dropped.
         *
         * Registered rather than left unhandled so that the drop is written
         * down where somebody looks for it, and so the SDK does not log a
         * missing handler for every one. See [ours] for why a message from a
         * bot is never published: a workflow that answers in a thread it
         * watches would otherwise trigger itself, for ever.
         */
        app.event(MessageBotEvent::class.java) { _, context ->
            lastEvents[id] = Instant.now()
            context.ack()
        }
    }

    /**
     * A mention, on its way to whoever is watching for one.
     *
     * Public for the reason [receive] is: a socket is the only other caller,
     * and a test that had to open one could not run without Slack.
     */
    fun publish(connectionId: Long, workspaceId: Long, mention: AppMentionEvent, slackWorkspaceId: String?) {
        lastEvents[connectionId] = Instant.now()
        val event = IncomingEvent(
            connectionId = connectionId,
            workspaceId = workspaceId,
            action = IncomingAction.MENTION,
            text = mention.text,
            context = buildMap {
                mention.channel?.let { put("channel", it) }
                mention.user?.let { put("user", it) }
                mention.ts?.let { put("ts", it) }
                // Where a reply goes: the thread if there is one, else the message.
                (mention.threadTs ?: mention.ts)?.let { put("threadTs", it) }
                // The same as for a message: which Slack this came from.
                put("connection", connectionId.toString())
                slackWorkspaceId?.let { put("slackWorkspaceId", it) }
                // And what was attached to it, where anything was.
                describe(mention.files)?.let { put("files", it) }
            },
        )
        // Worth an INFO line: "did Slack deliver anything" is the first question
        // asked when a trigger does not fire, and answering it should not need
        // DEBUG on a third-party package. The text is left out — a mention is
        // someone's message, and this is not the place it gets stored.
        log.info(
            "Slack mention received on connection {} (workspace {}, channel {}, {} file(s))",
            connectionId,
            workspaceId,
            mention.channel,
            filesOf(mention.files).size,
        )

        raise(connectionId, event)
    }

    /**
     * A message in a channel this connection can read.
     *
     * **Two events can come of one message.** [IncomingAction.MESSAGE] is raised
     * for every message that is not a bot's, and [IncomingAction.REPLY] as well
     * when it hangs under a thread — a reply is a message, and a definition
     * waiting on messages in a channel should not stop hearing them because
     * somebody used a thread. Which of the two a trigger wants is the trigger's
     * choice, and the two are matched separately.
     *
     * **Slack sends a mention twice.** An `@orknux` in a channel the bot reads
     * arrives as `app_mention` and again as `message`, so a connection carrying
     * a mention trigger and a message trigger fires both. That is Slack's own
     * doing rather than something to correct here — a message trigger that
     * silently skipped mentions would be the more surprising of the two.
     *
     * Public for the same reason [listeningConnectionIds] is: a socket is the
     * only other caller, and a test that had to open one could not run without
     * Slack. A real payload put through here is the whole path bar the wire.
     */
    fun receive(connectionId: Long, workspaceId: Long, message: MessageEvent, slackWorkspaceId: String?) =
        receive(connectionId, workspaceId, said(message), slackWorkspaceId)

    /**
     * The same, for the upload Slack delivers as its own event.
     *
     * A `message` with a `file_share` subtype, which the SDK models as a
     * different class carrying the same fields - so it is turned into the same
     * [Said] and walks the same path.
     */
    fun receive(
        connectionId: Long,
        workspaceId: Long,
        message: MessageFileShareEvent,
        slackWorkspaceId: String?,
    ) = receive(connectionId, workspaceId, said(message), slackWorkspaceId)

    private fun receive(connectionId: Long, workspaceId: Long, message: Said, slackWorkspaceId: String?) {
        lastEvents[connectionId] = Instant.now()
        /*
         * Handed on whole, rather than filtered here and handed on after.
         *
         * The loop guard asks who this connection posts as, and on a cold cache
         * that is a call to Slack — which must not stand between an arriving
         * message and the acknowledgement Slack wants inside three seconds. A
         * mention can be filtered on the socket thread because there is nothing
         * to ask about one; this cannot.
         */
        dispatcher.execute {
            try {
                deliver(connectionId, workspaceId, message, slackWorkspaceId)
            } catch (failure: Exception) {
                // Nobody is left to tell: the acknowledgement has gone back to
                // Slack already, and this thread is the end of the line.
                log.error("A Slack message on connection {} could not be handled", connectionId, failure)
            }
        }
    }

    /** One message, already off the socket thread. */
    private fun deliver(connectionId: Long, workspaceId: Long, message: Said, slackWorkspaceId: String?) {
        if (ours(connectionId, message)) return

        val context = buildMap {
            message.channel?.let { put("channel", it) }
            message.user?.let { put("user", it) }
            message.ts?.let { put("ts", it) }
            // Where a reply goes: the thread if there is one, else the message.
            (message.threadTs ?: message.ts)?.let { put("threadTs", it) }
            // Who wrote the message this hangs under, which is the whole of how
            // "a reply to one of ours" is decided. Only a thread reply has one.
            message.parentUserId?.let { put("parentUserId", it) }
            // `channel`, `im`, `mpim`, `group` - what a workflow reads to tell a
            // direct message from a channel, which the channel id does not say.
            message.channelType?.let { put("channelType", it) }
            /*
             * Which connection this arrived on.
             *
             * So a later node can answer back, or read the thread, through the
             * one it came from rather than through whichever the workspace
             * happens to list first: two Slack connections are two Slacks, and
             * the difference only shows up as somebody else's messages.
             */
            put("connection", connectionId.toString())
            slackWorkspaceId?.let { put("slackWorkspaceId", it) }
            // What was attached, where anything was. See [describe].
            describe(message.files)?.let { put("files", it) }
        }

        // The same INFO line a mention gets, and for the same reason: "did Slack
        // deliver anything" is the first question asked of a trigger that did
        // not fire. The text is left out - this is somebody's message, and this
        // is not the place it gets stored.
        log.info(
            "Slack message received on connection {} (workspace {}, channel {}, thread {}, {} file(s))",
            connectionId,
            workspaceId,
            message.channel,
            message.threadTs,
            // Counted rather than named: "did the upload arrive" is the first
            // question asked when an agent says it cannot see a file, and
            // answering it should not need DEBUG on somebody else's package.
            // The names are somebody's filenames and do not belong in a log.
            message.files.size,
        )

        /*
         * Through the same door the mention goes through, so a redelivered
         * message is dropped exactly as a redelivered mention is. Two events
         * off one message and two keys: a definition waiting on messages and
         * one waiting on replies are both entitled to this one.
         */
        raise(connectionId, IncomingEvent(connectionId, workspaceId, IncomingAction.MESSAGE, message.text, context))
        if (message.threadTs != null) {
            raise(connectionId, IncomingEvent(connectionId, workspaceId, IncomingAction.REPLY, message.text, context))
        }
    }

    /**
     * Whether this is something we wrote, and therefore not to be published.
     *
     * **The loop guard, and it is not optional.** A workflow that answers in a
     * thread it watches sees its own answer arrive as a reply to a message one
     * of our bots wrote — which is exactly what a reply trigger is looking for —
     * and starts itself again, and again.
     *
     * Two questions rather than one because Slack answers in two ways. A message
     * posted through the API carries a `bot_id` naming whoever posted it, which
     * catches every bot including other people's. And where an app posts as its
     * own bot user without one, the author is that user, so the connection's own
     * id is compared as well — resolved from the cache [SlackBotUsers] keeps,
     * never from a call made per message.
     */
    private fun ours(connectionId: Long, message: Said): Boolean {
        if (message.botId != null || message.fromBotProfile) {
            log.debug("A Slack message on connection {} came from a bot and was left alone", connectionId)
            return true
        }
        val author = message.user ?: return true
        if (author == botUsers.identify(connectionId).userId) {
            log.debug("A Slack message on connection {} was this connection's own", connectionId)
            return true
        }
        return false
    }

    /**
     * One message, whichever of Slack's events delivered it.
     *
     * An ordinary message, a mention and an upload are three classes in the SDK
     * with the same fields on them, and everything downstream of here cares
     * about the fields. Written out rather than handled three times: the loop
     * guard and the context are the two things that must not differ between
     * them, and they differed the day one of the three was simply not
     * registered.
     */
    private data class Said(
        val channel: String?,
        val user: String?,
        val ts: String?,
        val threadTs: String?,
        val parentUserId: String?,
        val channelType: String?,
        val text: String?,
        val files: List<com.slack.api.model.File>,
        /** Named by Slack when the API posted it; see [ours]. */
        val botId: String?,
        /** The other way Slack says the same thing. */
        val fromBotProfile: Boolean,
    )

    /**
     * The files on an event, which Slack leaves out rather than sending empty.
     *
     * Its own function because the getter is a platform type: reading it into
     * anything non-null compiles to a check that throws, and `orEmpty()` on
     * the value does not save it. Taken as nullable here, so the null is
     * handled where it arrives instead of where it lands.
     */
    private fun filesOf(files: List<com.slack.api.model.File>?): List<com.slack.api.model.File> =
        files ?: emptyList()

    private fun said(message: MessageEvent) = Said(
        channel = message.channel,
        user = message.user,
        ts = message.ts,
        threadTs = message.threadTs,
        parentUserId = message.parentUserId,
        channelType = message.channelType,
        text = message.text,
        files = filesOf(message.files),
        botId = message.botId,
        fromBotProfile = message.botProfile != null,
    )

    /**
     * An upload, read the same way.
     *
     * No `bot_id` on this one: Slack does not put it on a file share, so what
     * catches our own upload is the author check in [ours] - a file this
     * installation posted was posted as the connection's own bot user, which
     * that question already asks about.
     */
    private fun said(message: MessageFileShareEvent) = Said(
        channel = message.channel,
        user = message.user,
        ts = message.ts,
        threadTs = message.threadTs,
        parentUserId = message.parentUserId,
        channelType = message.channelType,
        text = message.text,
        files = filesOf(message.files),
        botId = null,
        fromBotProfile = false,
    )

    /**
     * What was attached, as a line a model and a workflow can both read.
     *
     * The bytes are not here and should not be: a file is fetched through the
     * Slack plugin, with the token, when something decides it wants it. What
     * this carries is enough to decide - the id `slack_readAttachment` takes,
     * the name, the type and the size - because the alternative is what
     * happened before it: an agent handed a message with no sign that anything
     * came with it, answering questions about a document it had never been
     * told existed.
     *
     * JSON, so a workflow expression can read a field out of it, and compact,
     * because this rides in the payload a model sees. Null where nothing was
     * attached, which keeps the key off every ordinary message.
     */
    private fun describe(files: List<com.slack.api.model.File>?): String? {
        /*
         * Null, not empty, for a message with nothing attached.
         *
         * Slack leaves the field out and the SDK's getter is a platform type,
         * so a `List<File>` parameter here compiles to a null check that
         * throws on every ordinary message - which is exactly what it did:
         * one NullPointerException per mention, inside the dispatcher, and no
         * trigger fired at all. Nullable at the door, once, rather than
         * remembered at each call.
         */
        if (files.isNullOrEmpty()) return null
        return files.joinToString(",", "[", "]") { file ->
            buildString {
                append("{")
                append("\"id\":\"").append(quoted(file.id)).append("\",")
                append("\"name\":\"").append(quoted(file.name ?: file.title)).append("\",")
                append("\"mimetype\":\"").append(quoted(file.mimetype)).append("\",")
                append("\"size\":").append(file.size ?: 0).append(",")
                /*
                 * Where the bytes are, which is what makes a picture fetchable
                 * without asking Slack who it belongs to first.
                 *
                 * Not a credential: `url_private` answers 403 to anybody
                 * without the bot token, so this is a location rather than a
                 * key - and the only thing that fetches it is the connection
                 * that already holds the token. See `SlackFiles`.
                 */
                append("\"url\":\"").append(quoted(file.urlPrivate)).append("\"")
                append("}")
            }
        }
    }

    /** A filename is somebody else's text, and it lands in JSON. */
    private fun quoted(value: String?): String =
        value.orEmpty().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ")

    /**
     * Whether this exact event has already been handed on, and remembering it
     * where it has not.
     *
     * Swept as it goes rather than on a timer: the sweep is a walk of a map
     * that holds a few minutes of one installation's Slack traffic, and doing
     * it when the map has grown is cheaper than a thread that wakes up all
     * night to find nothing.
     */
    private fun alreadySeen(connectionId: Long, action: IncomingAction, ts: String?): Boolean {
        // No ts is no identity. Slack always sends one; an event without it is
        // handed on rather than dropped on a guess.
        val key = "$connectionId:$action:${ts ?: return false}"
        val now = System.currentTimeMillis()
        if (delivered.size > MOST_REMEMBERED) {
            delivered.entries.removeIf { now - it.value > REMEMBER_FOR_MILLIS }
        }
        val before = delivered.put(key, now)
        return before != null && now - before < REMEMBER_FOR_MILLIS
    }

    /** Off the socket thread, so Slack's three seconds are not spent on a workflow. */
    private fun raise(connectionId: Long, event: IncomingEvent) {
        if (alreadySeen(connectionId, event.action, event.context["ts"])) {
            log.info(
                "A Slack {} on connection {} had already been delivered and was not raised again",
                event.action,
                connectionId,
            )
            return
        }
        dispatcher.execute {
            try {
                events.publishEvent(event)
            } catch (failure: Exception) {
                // Nobody is left to tell: the acknowledgement has gone back to
                // Slack already, and this thread is the end of the line.
                log.error("A Slack {} on connection {} could not be handled", event.action, connectionId, failure)
            }
        }
    }

    private fun close(id: Long) {
        val session = sessions.remove(id) ?: return
        // A session may be closed because the credentials changed, and a new
        // token may well be a different Slack user. Asked again rather than assumed.
        botUsers.forget(id)
        runCatching { session.socket.close() }
            .onFailure { log.warn("Could not close the Slack socket for connection {}", id, it) }
    }

    @PreDestroy
    fun stop() {
        reconciler.shutdownNow()
        dispatcher.shutdown()
        sessions.keys.toList().forEach(::close)
    }

    /** Which connections are listening, for the monitoring screen and the tests. */
    fun listeningConnectionIds(): Set<Long> = sessions.keys.toSet()

    private class SlackSession(
        val fingerprint: Int,
        /** The reconnect generation it was opened under; a newer one reopens it. */
        val generation: Long,
    ) {
        lateinit var socket: SlackSocket
        val openedAt: Instant = Instant.now()

        /** When anything last arrived, or a ping was last answered. */
        val lastHeard = AtomicReference(Instant.now())

        /** What Slack's latest `hello` said: which app, and how many connections it holds for it. */
        val appId = AtomicReference<String?>()
        val appConnections = AtomicReference<Int?>()
    }

    private class FailedAttempt(val fingerprint: Int, val at: Instant, val generation: Long)

    private class Failure(val reason: String, val at: Instant)

    /**
     * A connection that listens, with the two tokens it listens by.
     *
     * Not a data class, and [toString] carries neither token: a generated one
     * would put both into every log line that ever interpolated the object.
     */
    private class Listening(connection: WorkspaceConnection, val botToken: String, val appToken: String) {
        val name: String = connection.name
        val workspaceId: Long = connection.workspaceId

        /**
         * Enough to tell that the credentials changed, without holding onto them
         * anywhere they outlive the pass: a session opened with the old token
         * has to be replaced.
         */
        val fingerprint: Int = arrayOf(botToken, appToken).contentHashCode()

        override fun toString(): String = "Listening($name)"
    }

    /** True while the same credentials that just failed are still in their wait. */
    private fun Listening.waitingAfterFailure(failure: FailedAttempt?): Boolean {
        if (failure == null) return false
        if (failure.fingerprint != fingerprint) return false
        return failure.at.plusSeconds(properties.retryFailedSeconds).isAfter(Instant.now())
    }

    private companion object {
        val log = LoggerFactory.getLogger(SlackListener::class.java)

        /**
         * How long an event is remembered as already delivered.
         *
         * Slack gives up after three retries inside a minute, so this covers
         * that with room to spare - and stops well short of the hours over
         * which somebody legitimately sends the same words twice. A repeat has
         * its own `ts`, so the window is not what tells two messages apart; it
         * only bounds what is kept.
         */
        const val REMEMBER_FOR_MILLIS = 10L * 60 * 1000

        /** And how many, before the stale ones are walked out. */
        const val MOST_REMEMBERED = 5_000
    }
}
