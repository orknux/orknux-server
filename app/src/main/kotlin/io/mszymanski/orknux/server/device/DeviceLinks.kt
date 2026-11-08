package io.mszymanski.orknux.server.device

import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * What a person can lend their agents: a kind of their own software, reached
 * through a small Orknux client that runs beside it.
 *
 * [path] is the segment its door is under - `/api/browser`, `/api/desktop` -
 * and [said] is how a sentence names it to a model and a person.
 */
enum class DeviceKind(val path: String, val said: String, val howToConnect: String, val installUrl: String) {
    BROWSER(
        "browser",
        "browser",
        "Ask them to open the Orknux extension's side panel in Chrome and connect it to this server; the browser tools work while it is open.",
        "https://github.com/orknux/orknux-chrome",
    ),
    DESKTOP(
        "desktop",
        "computer",
        "Ask them to start Orknux for Windows or Orknux for Linux on their computer and connect it to this server; the desktop tools work while it is connected.",
        "https://github.com/orknux/orknux-windows",
    ),
    CLAUDE(
        "claude",
        "Claude Code session",
        "Start Claude Code with the Orknux channel plugin and it connects to this server; a chat with My Claude Code works while it runs.",
        "https://github.com/orknux/orknux-claude",
    ),
    SHELL(
        "terminal",
        "terminal",
        "Ask them to start Orknux Shell on their computer and sign in to this server; commands run there while it is open.",
        "https://github.com/orknux/orknux-shell",
    ),
    ;

    companion object {
        fun ofPath(path: String): DeviceKind? = entries.firstOrNull { it.path == path }
    }
}

/** One thing an agent asked the person's device to do, as the client is handed it. */
data class DeviceCommand(val id: String, val action: String, val arguments: JsonNode)

/** What the client answered: a result, or a sentence saying why there is none. */
data class DeviceAnswer(val result: JsonNode?, val error: String?)

/** What is known about a person's connected device. */
data class DevicePresence(val user: String, val kind: DeviceKind, val client: String?, val version: String?, val lastSeen: Instant)

/**
 * The devices people have connected - their Chrome, their Windows desktop - and
 * the calls waiting on them.
 *
 * A device is a person's, signed in with that person's token, and there is at
 * most one of each kind per person. The server never opens a connection to it:
 * the client asks for work ([next]) and posts each answer back ([answer]), so
 * it works from behind whatever network the person is on and needs nothing
 * listening on their machine.
 *
 * An agent's call ([call]) is queued for its person and kind and waited for on
 * the agent's own thread. The queue is in this process: on an installation
 * with more than one node, an agent running on a node the client is not asking
 * finds nothing connected there and is told so. A device is "connected" while
 * it has asked recently - see [Tunable.DEVICE_CONNECTED_SECONDS] - because a
 * client that was closed does not say goodbye.
 */
@Service
class DeviceLinks(private val tuning: Tuning) {

    /** The clock presence is measured by; a test moves it. */
    internal var clock: Clock = Clock.systemUTC()

    private class Link(val user: String, val kind: DeviceKind, val instance: String = "") {
        val queue = LinkedBlockingQueue<DeviceCommand>()
        val waiting = ConcurrentHashMap<String, CompletableFuture<DeviceAnswer>>()
        @Volatile var client: String? = null
        @Volatile var version: String? = null
        @Volatile var lastSeen: Instant = Instant.EPOCH
        @Volatile var asking: Int = 0
        /** When it began asking, after a while of not: which of two computers was connected last. */
        @Volatile var since: Instant = Instant.EPOCH
    }

    /**
     * One link per running client: [instance] is what the client says it is - a
     * Shell's id for its process - so two of a person's Shells on two computers
     * are two queues, not one that whichever asks first empties. A client that
     * says nothing is the single link of its kind it always was.
     */
    private data class Key(val user: String, val kind: DeviceKind, val instance: String)

    private val links = ConcurrentHashMap<Key, Link>()

    /**
     * Which running client a chat belongs to: the Shell that last sent a message
     * in it claims it, so the commands its agent runs go to that computer only.
     */
    private val claims = ConcurrentHashMap<Triple<String, DeviceKind, String>, String>()

    private fun linkOf(user: String, kind: DeviceKind, instance: String) =
        links.computeIfAbsent(Key(user, kind, instance)) { Link(user, kind, instance) }

    private fun linksOf(user: String, kind: DeviceKind): List<Link> =
        links.entries.filter { it.key.user == user && it.key.kind == kind }.map { it.value }

    /**
     * The one to use where no chat says which: the one connected most recently
     * of those connected now. By when it connected, not when it last asked - two
     * clients both asking all the time would otherwise take turns, and an agent's
     * clicks would land on one computer and then the other.
     */
    private fun newest(user: String, kind: DeviceKind): Link? =
        linksOf(user, kind).filter(::connected).maxByOrNull { it.since }

    /**
     * [user]'s clients of [kind] connected now, by the name each goes by - a
     * desktop client's own ("Linux on studio"), or its system where it says no
     * more - the one connected last first.
     */
    fun computers(user: String, kind: DeviceKind): List<Computer> =
        linksOf(user, kind).filter(::connected).sortedByDescending { it.since }
            .map { Computer(it.instance.ifEmpty { it.client ?: kind.said }, it.client, it.since) }

    /** [chat] belongs to [instance] of [user]'s clients of [kind] from now on. */
    fun claim(user: String, kind: DeviceKind, chat: String, instance: String) {
        claims[Triple(user, kind, chat)] = instance.take(CLIENT_LENGTH)
    }

    /** Links nobody has asked through for an hour, with nothing waiting: a client that stopped. */
    private fun forget() {
        val before = clock.instant().minusSeconds(FORGOTTEN_SECONDS)
        links.entries.removeIf { (_, link) -> link.asking == 0 && link.waiting.isEmpty() && link.lastSeen.isBefore(before) }
    }

    /**
     * The next command for [user]'s device of [kind], waiting up to
     * [waitMillis] for one, or null when none came. Asking is what keeps a
     * device connected.
     */
    fun next(user: String, kind: DeviceKind, client: String?, version: String?, waitMillis: Long, instance: String? = null): DeviceCommand? {
        forget()
        // A desktop client says which system it is and nothing more, and a person's Windows and Linux computers are
        // two computers: each its own link, by that name.
        val named = instance ?: if (kind == DeviceKind.DESKTOP) client else null
        val link = linkOf(user, kind, named?.take(CLIENT_LENGTH).orEmpty())
        if (!connected(link)) link.since = clock.instant()
        link.client = client?.take(CLIENT_LENGTH)
        link.version = version?.take(CLIENT_LENGTH)
        link.lastSeen = clock.instant()
        synchronized(link) { link.asking++ }
        try {
            return link.queue.poll(waitMillis, TimeUnit.MILLISECONDS)
        } finally {
            synchronized(link) { link.asking-- }
            link.lastSeen = clock.instant()
        }
    }

    /**
     * Leaves a command for [user]'s device of [kind] without waiting for an
     * answer: a chat message for a Claude Code session, whose reply arrives
     * later through a door of its own. False where nothing is connected to take it.
     */
    fun post(user: String, kind: DeviceKind, action: String, arguments: JsonNode): Boolean =
        leave(user, kind, action, arguments) != null

    /** As [post], answering the command left, so a caller can [withdraw] it if it is never taken; null where nothing is connected. */
    fun leave(user: String, kind: DeviceKind, action: String, arguments: JsonNode): DeviceCommand? {
        val link = newest(user, kind) ?: return null
        return DeviceCommand(UUID.randomUUID().toString(), action, arguments).also(link.queue::put)
    }

    /**
     * Takes back [command] if the device has not taken it yet, and says
     * whether it did: true means it never reached the device. The queue is in
     * this process, so a device that stopped asking - a session busy or gone, a
     * connection dropped - leaves a command sitting here until somebody looks.
     */
    fun withdraw(user: String, kind: DeviceKind, command: DeviceCommand): Boolean =
        linksOf(user, kind).any { it.queue.remove(command) }

    /** Hands the answer to the call waiting for it; false where nothing is waiting any more. */
    fun answer(user: String, kind: DeviceKind, id: String, answer: DeviceAnswer): Boolean {
        val waiting = linksOf(user, kind).firstNotNullOfOrNull { it.waiting.remove(id) } ?: return false
        return waiting.complete(answer)
    }

    /** The person's device of [kind], while it counts as connected; null otherwise. */
    fun presence(user: String, kind: DeviceKind): DevicePresence? {
        val link = newest(user, kind) ?: return null
        return DevicePresence(link.user, link.kind, link.client, link.version, link.lastSeen)
    }

    private fun connected(link: Link): Boolean =
        link.asking > 0 ||
            link.lastSeen.isAfter(clock.instant().minusSeconds(tuning[Tunable.DEVICE_CONNECTED_SECONDS].toLong()))

    /**
     * Asks [user]'s device of [kind] to do [action], and waits for its answer.
     *
     * Throws [DeviceNotConnectedException] where there is nothing to ask and
     * [DeviceUnansweredException] where it did not answer in time; an answer
     * that says the action failed is returned, since that is a fact for the
     * model rather than a fault.
     */
    fun call(
        user: String,
        kind: DeviceKind,
        action: String,
        arguments: JsonNode,
        waitSeconds: Long? = null,
        chat: String? = null,
        /** The computer to ask, by the name [computers] gives it: the agent's choice, where it made one. */
        computer: String? = null,
    ): DeviceAnswer {
        // A chat a client claimed goes to that client or to none: never to another of the person's computers
        // because that one happened to ask first.
        val claimed = chat?.let { claims[Triple(user, kind, it)] }
        val chosen = computer?.let { name -> linksOf(user, kind).filter(::connected).firstOrNull { it.instance.ifEmpty { it.client ?: kind.said } == name } }
        val link = if (computer != null) {
            chosen ?: throw DeviceNotConnectedException(kind)
        } else if (claimed != null) {
            links[Key(user, kind, claimed)]?.takeIf(::connected) ?: throw DeviceElsewhereException(kind)
        } else {
            // A Shell chat nobody claimed was written by a Shell that names no instance - an older one - so it goes to
            // one of those where there is one, never to a newer Shell that claims the chats it writes in.
            val connected = linksOf(user, kind).filter(::connected)
            val pool = if (kind == DeviceKind.SHELL && chat != null) connected.filter { it.instance.isEmpty() }.ifEmpty { connected } else connected
            pool.maxByOrNull { it.since } ?: throw DeviceNotConnectedException(kind)
        }
        val command = DeviceCommand(UUID.randomUUID().toString(), action, arguments)
        val answer = CompletableFuture<DeviceAnswer>()
        link.waiting[command.id] = answer
        link.queue.put(command)
        val seconds = waitSeconds ?: tuning[Tunable.DEVICE_CALL_SECONDS].toLong()
        return try {
            answer.get(seconds, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            // Not taken yet: take it back, so a device that comes back later does not do it unasked.
            link.queue.remove(command)
            throw DeviceUnansweredException(kind, seconds)
        } catch (failed: ExecutionException) {
            throw failed.cause ?: failed
        } finally {
            link.waiting.remove(command.id)
        }
    }

    private companion object {
        const val CLIENT_LENGTH = 120
        const val FORGOTTEN_SECONDS = 3600L
    }
}

/** One of a person's connected clients of a kind: what it is called, its system, and when it connected. */
data class Computer(val name: String, val system: String?, val since: Instant)

/** Said to the agent: the client this chat belongs to has gone, and none other is to be used in its place. */
class DeviceElsewhereException(kind: DeviceKind) : RuntimeException(
    "The ${kind.said} this chat belongs to - the one it was last written in - is not connected now, so nothing " +
        "was run. Another of the person's may be, on another computer, but this chat's commands run only where it is " +
        "being written. Ask the person to open it there again, or to send a message from where they want it run.",
)

/** Said to the agent, and so worded for one: there is nobody's device of this kind to drive. */
class DeviceNotConnectedException(kind: DeviceKind) : RuntimeException(
    "No ${kind.said} is connected for the person this conversation belongs to. ${kind.howToConnect}",
)

class DeviceUnansweredException(kind: DeviceKind, seconds: Long) : RuntimeException(
    "The ${kind.said} did not answer within $seconds seconds. What it shows may still have changed - take a " +
        "snapshot before acting again.",
)

/** Said to the agent: this conversation is nobody's, so there is no device it could mean. */
class DeviceNobodyException(kind: DeviceKind) : RuntimeException(
    "This conversation does not belong to a person - it is a workflow step, a Slack thread or something " +
        "else run on nobody's behalf - so there is no ${kind.said} for it to use. These tools work in a chat, " +
        "or a task somebody started.",
)
