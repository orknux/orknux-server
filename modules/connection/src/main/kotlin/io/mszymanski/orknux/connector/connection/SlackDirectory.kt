package io.mszymanski.orknux.connector.connection

import com.slack.api.model.ConversationType
import com.slack.api.model.User
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

/**
 * Which of Slack's two lookups a question is about, and never which of them a
 * caller has to know in advance.
 *
 * It exists because the *endpoints* differ - `conversations.list` for one,
 * `users.list` for the other - and for no other reason. Slack does not
 * differentiate when sending: `chat.postMessage` takes one `channel` argument
 * that accepts either id, because a direct message is a conversation. So both
 * [SlackDirectory.check] and [SlackDirectory.suggest] take this as a *narrowing*
 * and answer perfectly well without one, and every answer says which kind it
 * turned out to be.
 */
enum class SlackTargetKind {
    CHANNEL,
    USER,
}

/** What asking a Slack connection about a typed target came back with. */
enum class SlackTargetOutcome {

    /** Slack named it. */
    FOUND,

    /**
     * Slack answered, and nothing it can see goes by that. Advice and never a
     * verdict: see [SlackTargetCheck].
     */
    NOT_FOUND,

    /**
     * The question was never put. A scope the token does not carry, no token at
     * all, a rate limit, a workspace too large for one lookup. Kept apart from
     * [NOT_FOUND] because the two want opposite things done about them, and
     * because a field that reads as broken when the token merely cannot
     * introspect is worse than a field with no checking in it.
     *
     * **Half an answer is this and not [NOT_FOUND].** When no kind was given and
     * one of the two lookups was refused, a name absent from the half that could
     * be read is not a name that does not exist - it may be sitting in the half
     * that was never read. Ruling on it would be the one mistake this vocabulary
     * exists to prevent, so the merged answer keeps the refusal.
     */
    UNCHECKED,
}

/**
 * Whether a typed Slack user or channel is one a connection can see.
 *
 * **Advice, not a gate.** Nothing refuses a save on the strength of this. A
 * member who joined a minute ago, a private channel the bot was never invited
 * to, and an id pasted out of somebody else's message all read as [NOT_FOUND]
 * from here while being perfectly correct, so the wording of every answer says
 * what was observed rather than what should be done about it.
 *
 * @property message one line, ready to put on a screen. Never carries a
 *   credential.
 * @property id Slack's own id, when it was found.
 * @property label what Slack calls it - `#general`, `Alice Adams` - when it was
 *   found.
 * @property kind which of the two it turned out to be, when it was found. The
 *   answer to a question asked without one: a caller that did not know whether
 *   it held a channel or a person is told, and can fill that in rather than
 *   asking again.
 */
data class SlackTargetCheck(
    val outcome: SlackTargetOutcome,
    val message: String,
    val id: String? = null,
    val label: String? = null,
    val kind: SlackTargetKind? = null,
)

/**
 * One user or channel a connection can see, offered for somebody to pick.
 *
 * Picking one is a convenience and never the only way in: what a picker puts in
 * the field is [name], which is exactly what somebody could have typed, and the
 * field goes on accepting what nothing here offered.
 *
 * @property id Slack's own id - `C0123456789`, `U0123456789`.
 * @property name what the field is filled with when this is picked, and what a
 *   person recognises: `#general`, `@alice`.
 * @property kind which of the two this row is. Carried on the row rather than
 *   on the list, because one list holds both when no kind was asked for and a
 *   row that cannot say which it is cannot be drawn or acted on.
 * @property realName the member's own name where Slack has one and it says
 *   something the handle does not - `Alice Adams`. Always null for a channel,
 *   whose one name is [name].
 */
data class SlackSuggestion(
    val id: String,
    val name: String,
    val kind: SlackTargetKind,
    val realName: String? = null,
)

/**
 * What a connection has to offer against what somebody has typed so far.
 *
 * The three outcomes are [SlackTargetCheck]'s, and mean here what they mean
 * there. [SlackTargetOutcome.FOUND] is at least one match. [SlackTargetOutcome.NOT_FOUND]
 * is a list read to the end with nothing in it that matches - still advice,
 * because the list is minutes old and because a private channel this bot is not
 * in was never in it. [SlackTargetOutcome.UNCHECKED] is no suggestions and a
 * reason: a token with no read scope, a Slack that is rate-limiting, a list
 * still being read.
 *
 * @property message one line, and empty when there is nothing worth saying -
 *   which is the usual answer. Never carries a credential.
 * @property complete whether [matches] is everything that matches. False when
 *   Slack was not read to the end, and false when more matched than are
 *   returned; both mean the same thing to somebody typing, which is that there
 *   may be more and narrowing will find it. When both lists were searched it is
 *   false unless *both* were read whole, because half a search is not a search.
 */
data class SlackSuggestions(
    val outcome: SlackTargetOutcome,
    val message: String,
    val matches: List<SlackSuggestion> = emptyList(),
    val complete: Boolean = false,
)

/**
 * Asks a Slack connection about the users and channels it can see: whether the
 * one somebody typed is among them, and which ones they might have meant.
 *
 * Here rather than in the server for the reason [OutgoingMessages] is: this is
 * where the token lives, and the token is decrypted in this module and nowhere
 * else. The server asks a question and is told an answer.
 *
 * **Two questions, one vocabulary.** [check] is one lookup about one name, and
 * [suggest] is a list to pick from; they answer with the same
 * [SlackTargetOutcome] and the same sentences, because they fail in the same
 * ways and a form draws them a few pixels apart. Where they differ is what they
 * cost: [check] reads one page and reports what it could not settle, while
 * [suggest] pages, caches per connection, and is bounded on both.
 *
 * **Neither of them needs to be told which kind.** [SlackTargetKind] is a
 * narrowing and not a requirement, because it describes Slack's lookups rather
 * than Slack's addressing - one `chat.postMessage` sends to either. A question
 * asked without one searches both halves and merges them, and the merge has
 * three rules worth stating:
 *
 *  - **A sigil or an id shape settles it and costs nothing.** `#support` is a
 *    channel, `@alice`, an address and a `U…` id are people, and a `C…` id is a
 *    channel. Only a bare name that could be either is asked about twice.
 *  - **Channels come before people at equal rank.** Something has to break the
 *    tie and this is the side to break it towards: a channel name is unique in
 *    a Slack and a display name is not, so the row a person sees first is the
 *    one that can only mean one thing.
 *  - **Half an answer never becomes a verdict.** Found in one half is
 *    [SlackTargetOutcome.FOUND]. Absent from both, both read whole, is
 *    [SlackTargetOutcome.NOT_FOUND]. Absent from the half that answered while
 *    the other was refused is [SlackTargetOutcome.UNCHECKED] and the refusal's
 *    own sentence, because the name may well be in the half nobody read.
 *
 * **The scope is the whole design.** `users.list` and `users.info` need
 * `users:read`, and the channel lookups need `channels:read` and `groups:read`.
 * A bot token that posts perfectly well carries none of them, because nothing
 * about *sending* requires them. So a token that cannot introspect has to
 * produce a different answer from a name that is wrong, in words that say the
 * connection is fine and the typing has not been judged. That is
 * [SlackTargetOutcome.UNCHECKED], and suggesting degrades to it rather than to
 * a failure: an empty picker with no reason under it reads as a broken
 * connection, which is the one impression this must not leave.
 *
 * A merged answer names the scopes that are actually missing and only those. A
 * token carrying `channels:read` and not `users:read` is the case this merge was
 * built for - it used to be shown nothing at all, because nothing could tell it
 * which half to ask - and what it is shown now is every channel, and one line
 * saying that the members need `users:read`.
 *
 * **What a merged question costs, and why nothing had to change for it.** A
 * merged miss reads two lists where a narrowed one reads one, and that is one
 * request against each of two budgets rather than two against one: Slack meters
 * per method, so `conversations.list` and `users.list` do not share a bucket and
 * a merged read spends the same share of each as a narrowed read of that half
 * did. [suggest] pays it once and not per keystroke, because the two entries it
 * fills are the two the cache already keeps and the next question - merged or
 * not - is a lookup in a map. [check] is uncached by design and pays for both
 * halves each time, which is why a sigil, an address or an id is taken as the
 * answer it is rather than confirmed against the other half.
 *
 * The one bound worth restating is [SlackListingCache]'s: thirty-two lists, two
 * per connection, so sixteen Slacks being typed against at once. A merged
 * question keeps both of one connection's entries warm rather than one, which is
 * what that cap was always counted in.
 *
 * **Neither of them gates anything.** The field they describe is free text and
 * stays free text. `createAction` and `updateAction` do not call either.
 *
 * **No token reaches a message or a log.** The credential is passed to the SDK
 * and is not interpolated into anything that comes back out; a failure is logged
 * by connection id.
 */
@Component
class SlackDirectory(
    private val connections: WorkspaceConnectionRepository,
    /** Where the bot token comes from: the connection's own copy, or a workspace secret. */
    private val credentials: ConnectionCredentials,
    slackClients: SlackClients,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** For the same reason [OutgoingMessages] does not use `Slack.getInstance()`. */
    private val slack = slackClients.webApi

    /**
     * Held by the component rather than by a call, which is the whole point of
     * it. See [SlackListingCache] for how long an entry lives and what bounds
     * it.
     */
    private val cache = SlackListingCache()

    /**
     * @param kind which lookup to put the question to, or null to put it to
     *   both and merge the answers.
     * @param typed exactly what is in the field: `#general`, `general`,
     *   `@alice`, an address, or an id pasted out of Slack.
     */
    fun check(connectionId: Long, kind: SlackTargetKind?, typed: String): SlackTargetCheck {
        val name = typed.trim()
        if (name.isEmpty()) return unchecked("")

        val opening = open(connectionId)
        if (opening is Opening.Shut) return unchecked(opening.why)
        val token = (opening as Opening.Ready).token

        val halves = asked(kind, name).map { half(connectionId, token, it, name) }

        // One half is its own answer, whether it was named or narrowed to. Two
        // is the only case there is anything to merge.
        return halves.singleOrNull()?.check ?: merge(name, halves)
    }

    /**
     * The users or channels this connection can see that match what has been
     * typed so far, most likely first.
     *
     * **Filtered here and not by Slack**, because neither endpoint takes a
     * search term: the list is read whole, once, and matched in memory. That is
     * what the cache is for, and it is why an empty [typed] is a perfectly good
     * question - it asks for the first few of everything, which is what a
     * picker shows when it opens.
     *
     * **A merged question reads the two lists that already exist.** The cache is
     * keyed by connection and kind, and asking without a kind asks it for both
     * of those keys rather than inventing a third. So a picker that has been
     * open on channels and is then asked for everything pays for the member list
     * only, and the two answers stay one read apart from each other for as long
     * as they are worth keeping.
     *
     * @param kind which list to read, or null to read both and offer one list.
     * @param typed what is in the field so far, with or without its `#` or `@`.
     */
    fun suggest(connectionId: Long, kind: SlackTargetKind?, typed: String): SlackSuggestions {
        val opening = open(connectionId)
        if (opening is Opening.Shut) return none(opening.why)
        val token = (opening as Opening.Ready).token

        val name = typed.trim()
        val lists = asked(kind, name).map { it to listing(connectionId, token, it) }

        return offer(lists, name)
    }

    /**
     * Slack's own id for what somebody typed into a target field, or null when
     * nothing this connection can see answers to it.
     *
     * **Why sending needs this.** `chat.postMessage` takes one `channel`
     * argument, and what that argument accepts is an id or a *channel* name.
     * "#general" and "general" reach a channel; "@alice" and "alice" reach
     * nobody, because a bot token does not resolve a handle there. A person is
     * reached by their user id, which the same argument does take - a direct
     * message is a conversation. So a field that has to accept both kinds of
     * name has to be resolved before it is posted to. The alternative is a rule
     * saying "type an id for a person", and a rule like that is forgotten by
     * somebody whose message then goes nowhere and says it went somewhere.
     *
     * **Cheap on purpose.** An id resolves to itself with no call at all. An
     * address has its own endpoint, which is a cheaper tier than either list.
     * A name is matched against the lists the cache already holds for a picker,
     * so a workflow sending in a loop does not spend the connection's rate
     * limit that the cache exists to protect - see [SlackListingCache], whose
     * reason for existing names this caller.
     *
     * **Null is not a refusal.** A name nothing answers to is handed to Slack
     * as it was typed: a private channel this bot is in but that the listing
     * never returned still posts by name, and where it genuinely is wrong
     * Slack's own `channel_not_found` is a better sentence than a guess made
     * here. Nothing about this gates a save, and `targetName` stays free text.
     */
    fun resolve(connectionId: Long, typed: String): String? {
        val name = typed.trim()
        if (name.isEmpty()) return null

        // Already Slack's own answer. Verifying it would be a call spent
        // proving what the caller is about to find out anyway.
        if (CHANNEL_ID.matches(name) || USER_ID.matches(name)) return name

        val opening = open(connectionId)
        if (opening is Opening.Shut) return null
        val token = (opening as Opening.Ready).token

        if (EMAIL.matches(name)) {
            // Never throws, for the reason the whole method answers with null:
            // this runs on the way to a send, and Slack being unreachable
            // belongs to the send's own report rather than to a stack trace
            // out of a lookup nobody asked for.
            return try {
                val answer = slack.methods(token).usersLookupByEmail { it.email(name) }
                if (answer.isOk) answer.user?.id else null
            } catch (failure: Exception) {
                log.warn("Could not look up the address on connection {}", connectionId, failure)
                null
            }
        }

        // The same narrowing a lookup does, for the same reason: a "#" or an
        // "@" says which half was meant, and only a bare name costs both.
        val wanted = name.removePrefix("#").removePrefix("@")
        return asked(null, name)
            .asSequence()
            .flatMap { listing(connectionId, token, it).entries.asSequence() }
            .firstOrNull { it.name.removePrefix("#").removePrefix("@").equals(wanted, ignoreCase = true) }
            ?.id
    }

    /**
     * Which lookups one question turns into.
     *
     * A named kind is that kind and nothing else - the behaviour every existing
     * caller has. A question with no kind is narrowed by what was typed where
     * Slack's own notation settles it, and only a bare name that could be either
     * costs two lookups. The sigils are not a guess: somebody who typed `#` said
     * which half they meant, and an id's first letter is Slack's own answer.
     */
    private fun asked(kind: SlackTargetKind?, typed: String): List<SlackTargetKind> = when {
        kind != null -> listOf(kind)
        typed.startsWith("#") || CHANNEL_ID.matches(typed) -> listOf(SlackTargetKind.CHANNEL)
        typed.startsWith("@") || USER_ID.matches(typed) || EMAIL.matches(typed) -> listOf(SlackTargetKind.USER)
        else -> listOf(SlackTargetKind.CHANNEL, SlackTargetKind.USER)
    }

    /**
     * One lookup, and never an exception.
     *
     * Caught per half rather than around the pair, so that a channel lookup that
     * fell over does not throw away a member lookup that answered. The sentence
     * is the one a single-kind question has always been given.
     */
    private fun half(connectionId: Long, token: String, kind: SlackTargetKind, name: String): Half = try {
        when (kind) {
            SlackTargetKind.CHANNEL -> channel(token, name)
            SlackTargetKind.USER -> user(token, name)
        }
    } catch (failure: Exception) {
        // Logged rather than passed on. The SDK's own exception text is a
        // response body and has never held a token, but a message that goes
        // to a screen is not the place to start relying on that.
        log.warn("Could not ask Slack about a {} on connection {}", kind, connectionId, failure)
        Half(kind, unchecked("Not checked - Slack could not be reached."))
    }

    /**
     * Two answers about one name, made into one.
     *
     * Reached only when nothing about what was typed said which half it belonged
     * to, so the two are genuinely being weighed against each other. See the
     * class comment for why a channel wins a tie and why a refusal outranks an
     * absence.
     */
    private fun merge(name: String, halves: List<Half>): SlackTargetCheck {
        val hit = halves.firstOrNull { it.check.outcome == SlackTargetOutcome.FOUND }
        if (hit != null) {
            val other = halves.firstOrNull { it !== hit && it.check.outcome == SlackTargetOutcome.FOUND }
                ?: return hit.check
            // Both. Rare, entirely legal, and worth saying out loud: the field
            // takes one value and the person is the only one who knows which
            // they meant.
            return hit.check.copy(message = alsoTheOther(hit.check, other.kind))
        }

        val refused = halves.filter { it.check.outcome == SlackTargetOutcome.UNCHECKED }
        if (refused.isEmpty()) return notFound(nothingAnywhere(name))

        return unchecked(whyRefused(refused))
    }

    /** Both halves named something, in the one line there is room for. */
    private fun alsoTheOther(hit: SlackTargetCheck, other: SlackTargetKind): String {
        val also = if (other == SlackTargetKind.CHANNEL) "a channel" else "a member"
        return "${hit.message.take(TYPED_SHOWN)} - $also goes by that too; the # or @ picks which."
    }

    private fun nothingAnywhere(name: String) =
        "No channel or member called ${name.take(TYPED_SHOWN)} - a private one or a new joiner looks the same."

    /**
     * Why a merged answer is [SlackTargetOutcome.UNCHECKED], naming the scopes
     * that are actually missing and no others.
     *
     * The point of the sentence is what somebody does next, and "add
     * `users:read`" is only useful advice when `users:read` is the scope that is
     * missing. A token short of both is told both; a token short of one is told
     * that one, whatever the other half happened to answer. Anything that was
     * refused for some other reason - a rate limit, a revoked token - keeps its
     * own sentence, because a scope list would be the wrong thing to act on.
     */
    private fun whyRefused(refused: List<Half>): String {
        val wanting = refused.map { it.missing }
        if (wanting.all { it != null }) {
            val scopes = scopeList(wanting.filterNotNull().flatten())
            return "Not checked - this connection's bot token is missing $scopes."
        }
        return refused.first { it.missing == null }.check.message
    }

    /**
     * A channel by id when it looks like one, and by name otherwise.
     *
     * Both types are asked for in the one call, because the honest question is
     * what this connection can see and a list of only the public half presented
     * as the whole would rule out private channels it never looked at. A token
     * that can read one and not the other therefore gets "not checked", which is
     * true.
     */
    private fun channel(token: String, typed: String): Half {
        val kind = SlackTargetKind.CHANNEL
        val name = typed.removePrefix("#")

        if (CHANNEL_ID.matches(name)) {
            val answer = slack.methods(token).conversationsInfo { it.channel(name) }
            if (answer.isOk) {
                val hit = answer.channel
                return Half(kind, found(kind, hit.id, "#${hit.name}", "#${hit.name}"))
            }
            return when (answer.error) {
                CHANNEL_NOT_FOUND -> Half(kind, notFound("No channel with the id $name."))
                else -> refused(kind, answer.error, CHANNEL_SCOPES)
            }
        }

        val answer = slack.methods(token).conversationsList {
            it.limit(PAGE)
                .excludeArchived(false)
                .types(listOf(ConversationType.PUBLIC_CHANNEL, ConversationType.PRIVATE_CHANNEL))
        }
        if (!answer.isOk) return refused(kind, answer.error, CHANNEL_SCOPES)

        val hit = answer.channels.orEmpty().firstOrNull {
            name.equals(it.name, ignoreCase = true) || name.equals(it.nameNormalized, ignoreCase = true)
        }
        if (hit != null) return Half(kind, found(kind, hit.id, "#${hit.name}", "#${hit.name}"))

        // Nothing beyond the first page is read, so nothing beyond it is ruled
        // out either. Saying "no such channel" off a list that was cut short is
        // exactly the wrong answer to give about a name somebody knows is right.
        if (!answer.responseMetadata?.nextCursor.isNullOrBlank()) {
            return Half(kind, unchecked("Not checked - more channels here than one lookup reads."))
        }

        return Half(kind, notFound("No channel called #$name - though a private one this bot is not in looks the same."))
    }

    /** A member by id, by address, or by whichever of the several names Slack keeps. */
    private fun user(token: String, typed: String): Half {
        val kind = SlackTargetKind.USER
        val name = typed.removePrefix("@")

        if (USER_ID.matches(name)) {
            val answer = slack.methods(token).usersInfo { it.user(name) }
            return when {
                answer.isOk -> Half(kind, found(answer.user))
                answer.error == USER_NOT_FOUND ->
                    Half(kind, notFound("No member with the id $name."))
                else -> refused(kind, answer.error, USER_SCOPES)
            }
        }

        // An address has its own method and its own scope. Worth the branch:
        // without it an address falls through to the name match below, matches
        // nothing, and is reported as a member who does not exist.
        if (EMAIL.matches(name)) {
            val answer = slack.methods(token).usersLookupByEmail { it.email(name) }
            return when {
                answer.isOk -> Half(kind, found(answer.user))
                answer.error == USERS_NOT_FOUND || answer.error == USER_NOT_FOUND ->
                    Half(kind, notFound("No member with the address $name."))
                else -> refused(kind, answer.error, EMAIL_SCOPES)
            }
        }

        val answer = slack.methods(token).usersList { it.limit(PAGE) }
        if (!answer.isOk) return refused(kind, answer.error, USER_SCOPES)

        val hit = answer.members.orEmpty().firstOrNull { !it.isDeleted && it.answersTo(name) }
        if (hit != null) return Half(kind, found(hit))

        if (!answer.responseMetadata?.nextCursor.isNullOrBlank()) {
            return Half(kind, unchecked("Not checked - more members here than one lookup reads."))
        }

        return Half(kind, notFound("No member called @$name - though somebody who just joined looks the same."))
    }

    /**
     * What the connection can be asked with, or the one line saying why it
     * cannot be asked at all.
     *
     * One place, because [check] and [suggest] are refused for the same four
     * reasons and a second copy of these sentences is a second copy to keep
     * short.
     */
    /**
     * What a member is called, by Slack's id for them: their display name, else
     * their real name, else their handle - or null where it cannot be asked.
     *
     * For a report that names who prompted a model, which would otherwise show
     * `U04…`. Never throws: the name is a courtesy beside a row that is written
     * either way.
     */
    fun memberName(connectionId: Long, userId: String): String? {
        if (!USER_ID.matches(userId)) return null
        val opening = open(connectionId)
        if (opening is Opening.Shut) return null
        val token = (opening as Opening.Ready).token
        return try {
            val answer = slack.methods(token).usersInfo { it.user(userId) }
            val user = answer.user?.takeIf { answer.isOk } ?: return null
            sequenceOf(user.profile?.displayName, user.realName, user.profile?.realName, user.name)
                .firstOrNull { !it.isNullOrBlank() }
        } catch (failure: Exception) {
            log.warn("Could not look up member {} on connection {}", userId, connectionId, failure)
            null
        }
    }

    private fun open(connectionId: Long): Opening {
        val connection = connections.findByIdOrNull(connectionId)
            ?: return Opening.Shut("That connection no longer exists.")

        if (connection.type != ConnectionType.SLACK) return Opening.Shut("Not a Slack connection.")

        val token = credentials.secretOf(connection).credential
            ?: return Opening.Shut("Not checked - no bot token stored.")

        // The token that opens the socket is not the token that looks anything
        // up, and the same sentence [OutgoingMessages] gives about posting.
        if (token.startsWith(APP_TOKEN_PREFIX)) {
            return Opening.Shut("Not checked - needs the xoxb- bot token, not the app-level one.")
        }

        return Opening.Ready(token)
    }

    /** Whether there is a token to ask with. */
    private sealed interface Opening {
        data class Ready(val token: String) : Opening

        /** @property why one line, ready to show. */
        data class Shut(val why: String) : Opening
    }

    /**
     * One half of a merged answer, and the whole of an unmerged one.
     *
     * [missing] rather than a message to match on: whether a merge may name a
     * scope is a fact about what Slack said, and reading it back out of a
     * sentence meant for a screen would break the first time the sentence was
     * reworded.
     *
     * @property missing the scopes Slack said were wanting, and null when it
     *   refused for any other reason or did not refuse at all.
     */
    private class Half(
        val kind: SlackTargetKind,
        val check: SlackTargetCheck,
        val missing: List<String>? = null,
    )

    private fun refused(kind: SlackTargetKind, error: String?, scopes: List<String>) =
        Half(kind, refusal(error, scopes), scopes.takeIf { error in SCOPE_REFUSALS })

    /** One connection's copy of one list, or an empty one that says it is still being read. */
    private fun listing(connectionId: Long, token: String, kind: SlackTargetKind): SlackListing =
        // A miss is filled here, on this call. Being read is a state and not an
        // emptiness, which is why the wait ending has an answer of its own
        // rather than coming back as a list with nothing in it.
        cache.listing(SlackListingKey(connectionId, kind)) { read(connectionId, kind, token) }
            ?: SlackListing(emptyList(), complete = false, stoppedOn = BEING_READ)

    /**
     * Reads one of the two lists whole, as far as the caps allow.
     *
     * Never throws, because this runs inside the cache's single read and a
     * caller waiting on it wants a listing that says it stopped short rather
     * than an exception in somebody else's stack.
     */
    private fun read(connectionId: Long, kind: SlackTargetKind, token: String): SlackListing = try {
        when (kind) {
            SlackTargetKind.CHANNEL -> readChannels(token)
            SlackTargetKind.USER -> readMembers(token)
        }
    } catch (failure: Exception) {
        log.warn("Could not read the {} list of connection {}", kind, connectionId, failure)
        SlackListing(emptyList(), complete = false, stoppedOn = NOT_REACHED)
    }

    /**
     * Archived channels are left out here and counted in by [check], which
     * looks inconsistent and is not: a channel that cannot be posted to has no
     * business being offered, and one somebody has already typed is still worth
     * saying exists.
     */
    private fun readChannels(token: String): SlackListing {
        val entries = mutableListOf<SlackSuggestion>()
        var cursor: String? = null

        repeat(MOST_PAGES) {
            val answer = slack.methods(token).conversationsList { request ->
                request.limit(READ_PAGE)
                    .excludeArchived(true)
                    .types(listOf(ConversationType.PUBLIC_CHANNEL, ConversationType.PRIVATE_CHANNEL))
                cursor?.takeIf { it.isNotBlank() }?.let(request::cursor)
                request
            }
            if (!answer.isOk) return listing(entries, complete = false, stoppedOn = answer.error ?: NOT_REACHED)

            answer.channels.orEmpty().forEach { channel ->
                val id = channel.id ?: return@forEach
                val name = channel.name ?: channel.nameNormalized ?: return@forEach
                entries += SlackSuggestion(id, "#$name", SlackTargetKind.CHANNEL)
            }

            cursor = answer.responseMetadata?.nextCursor
            if (cursor.isNullOrBlank()) return listing(entries, complete = true)
        }

        // Slack has more and the cap says stop. Reported as incomplete, which is
        // the difference between a short list and a wrong one.
        return listing(entries, complete = false)
    }

    private fun readMembers(token: String): SlackListing {
        val entries = mutableListOf<SlackSuggestion>()
        var cursor: String? = null

        repeat(MOST_PAGES) {
            val answer = slack.methods(token).usersList { request ->
                request.limit(READ_PAGE)
                cursor?.takeIf { it.isNotBlank() }?.let(request::cursor)
                request
            }
            if (!answer.isOk) return listing(entries, complete = false, stoppedOn = answer.error ?: NOT_REACHED)

            answer.members.orEmpty().forEach { member ->
                if (member.isDeleted) return@forEach
                val id = member.id ?: return@forEach
                val handle = member.name ?: return@forEach
                entries += SlackSuggestion(id, "@$handle", SlackTargetKind.USER, member.ownName(handle))
            }

            cursor = answer.responseMetadata?.nextCursor
            if (cursor.isNullOrBlank()) return listing(entries, complete = true)
        }

        return listing(entries, complete = false)
    }

    /**
     * One entry per id, whatever Slack sent.
     *
     * A cursor that hands back a page already seen is a thing that happens, and
     * the same channel drawn twice in a picker is the kind of defect people
     * report rather than explain away.
     */
    private fun listing(entries: List<SlackSuggestion>, complete: Boolean, stoppedOn: String? = null) =
        SlackListing(entries.distinctBy { it.id }, complete, stoppedOn)

    /**
     * Matches, in the order somebody would expect to see them, and the line to
     * put under them.
     *
     * The one rule worth stating: nothing concludes anything from a list that
     * stopped short. No matches in a complete list is [SlackTargetOutcome.NOT_FOUND];
     * no matches in a partial one is [SlackTargetOutcome.UNCHECKED] and the
     * reason it is partial, because the name may be in the part that was never
     * read. With two lists that rule is the same rule and is what makes half a
     * search an [SlackTargetOutcome.UNCHECKED] rather than a "no such channel".
     */
    private fun offer(lists: List<Pair<SlackTargetKind, SlackListing>>, typed: String): SlackSuggestions {
        val wanted = typed.removePrefix("#").removePrefix("@")

        val matched = lists
            .flatMap { (_, listing) -> listing.entries }
            .mapNotNull { entry -> rank(entry, wanted)?.let { it to entry } }
            .sortedWith(
                compareBy(
                    { (rank, _) -> rank },
                    // The tie-break across the two kinds. Channels first: a
                    // channel name means one thing in a Slack and a display
                    // name need not, so the row somebody reads first is the
                    // unambiguous one.
                    { (_, entry) -> if (entry.kind == SlackTargetKind.CHANNEL) CHANNELS_FIRST else PEOPLE_AFTER },
                    { (_, entry) -> entry.name.lowercase() },
                ),
            )
            .map { (_, entry) -> entry }

        val shown = matched.take(MOST_SHOWN)
        val whole = lists.all { (_, listing) -> listing.complete }
        val complete = whole && shown.size == matched.size

        if (shown.isNotEmpty()) {
            return SlackSuggestions(
                SlackTargetOutcome.FOUND,
                if (complete) "" else partial(lists),
                shown,
                complete,
            )
        }

        if (!whole) return none(reason(lists))

        return SlackSuggestions(
            SlackTargetOutcome.NOT_FOUND,
            nothingMatches(lists.map { (kind, _) -> kind }, typed),
            complete = true,
        )
    }

    /**
     * How well one entry answers to what was typed, or null when it does not.
     *
     * Exact, then what starts with it, then what contains it - which is the
     * order somebody scanning a list reads them in. The id is matched too, so
     * that one pasted out of Slack finds its own row instead of emptying the
     * list.
     */
    private fun rank(entry: SlackSuggestion, wanted: String): Int? {
        // Nothing typed yet, so everything answers equally and the sort falls
        // through to the kind and then the name.
        if (wanted.isBlank()) return EXACTLY
        val fields = listOfNotNull(entry.name.removePrefix("#").removePrefix("@"), entry.realName, entry.id)
        return fields.minOf { field ->
            when {
                field.equals(wanted, ignoreCase = true) -> EXACTLY
                field.startsWith(wanted, ignoreCase = true) -> FROM_THE_START
                field.contains(wanted, ignoreCase = true) -> SOMEWHERE
                else -> NOT_AT_ALL
            }
        }.takeIf { it != NOT_AT_ALL }
    }

    /**
     * Why a list somebody is being shown is not the whole of it.
     *
     * The case this is really for is the last one: channels read, members
     * refused for want of `users:read`, and a picker quietly showing half a
     * Slack. Saying "channels only" and naming the scope is the difference
     * between a list that looks short and a list that explains itself.
     */
    private fun partial(lists: List<Pair<SlackTargetKind, SlackListing>>): String {
        val short = lists.filter { (_, listing) -> !listing.complete }
        if (short.isEmpty()) return "Showing the first $MOST_SHOWN - keep typing to narrow it down."

        val silent = short.singleOrNull()?.takeIf { lists.size > 1 }
        val wanting = silent?.let { (kind, listing) -> missingScopes(kind, listing) }
        if (silent != null && wanting != null) {
            val left = if (silent.first == SlackTargetKind.USER) "Channels" else "Members"
            return "$left only - this connection's bot token is missing ${scopeList(wanting)}."
        }

        val (kind, listing) = short.first()
        return when {
            listing.stoppedOn == BEING_READ -> "Still reading this Slack's ${plural(kind)} - try again in a moment."
            listing.stoppedOn == RATELIMITED -> "Slack is rate-limiting this connection, so this list is partial."
            listing.stoppedOn == null -> "More ${plural(kind)} here than one lookup reads - keep typing to narrow it."
            else -> "Slack stopped answering part way, so this list is partial."
        }
    }

    /**
     * Why there is nothing to show, in the words [check] would have used.
     *
     * Deliberately the same sentences. A person meets these under one field or
     * the other and there is no version of this where two wordings for a
     * missing scope help them. Where every list that stopped short stopped on a
     * scope, they are named together and only they are named - one line, whether
     * that is one scope or three.
     */
    private fun reason(lists: List<Pair<SlackTargetKind, SlackListing>>): String {
        val short = lists.filter { (_, listing) -> !listing.complete }
        val wanting = short.map { (kind, listing) -> missingScopes(kind, listing) }
        if (wanting.isNotEmpty() && wanting.all { it != null }) {
            val scopes = scopeList(wanting.filterNotNull().flatten())
            return "Not checked - this connection's bot token is missing $scopes."
        }

        val (kind, listing) = short.first { (aKind, aListing) -> missingScopes(aKind, aListing) == null }
        return when (listing.stoppedOn) {
            null -> "Not checked - more ${plural(kind)} here than one lookup reads."
            NOT_REACHED -> "Not checked - Slack could not be reached."
            BEING_READ -> "Not checked - this Slack is still being read. Try again in a moment."
            else -> refusal(listing.stoppedOn, scopesFor(kind)).message
        }
    }

    /** The scopes a read was refused for, and null when it stopped short of Slack for any other reason. */
    private fun missingScopes(kind: SlackTargetKind, listing: SlackListing): List<String>? =
        scopesFor(kind).takeIf { listing.stoppedOn in SCOPE_REFUSALS }

    private fun nothingMatches(kinds: List<SlackTargetKind>, typed: String): String {
        val both = kinds.size > 1
        val channels = kinds.singleOrNull() == SlackTargetKind.CHANNEL
        return when {
            typed.isBlank() && both -> "No channels or members this connection can see."
            typed.isBlank() && channels -> "No channels this connection can see."
            typed.isBlank() -> "No members this connection can see."
            // The same caveat [check] gives, for the same reason: a list read to
            // the end is still not everything that exists.
            both -> "No channel or member matches ${typed.take(TYPED_SHOWN)} - " +
                "a private one or a new joiner looks the same."
            channels ->
                "No channel matches ${typed.take(TYPED_SHOWN)} - though a private one this bot is not in looks the same."
            else -> "No member matches ${typed.take(TYPED_SHOWN)} - though somebody who just joined looks the same."
        }
    }

    private fun plural(kind: SlackTargetKind) = if (kind == SlackTargetKind.CHANNEL) "channels" else "members"

    private fun scopesFor(kind: SlackTargetKind) =
        if (kind == SlackTargetKind.CHANNEL) CHANNEL_SCOPES else USER_SCOPES

    /**
     * The scopes, written the one way, however many there are.
     *
     * Assembled rather than kept as three finished sentences, because a merged
     * refusal names the scopes of both halves and a fourth stored sentence for
     * that combination is a fourth to keep under 120 characters.
     */
    private fun scopeList(scopes: List<String>): String {
        val named = scopes.distinct()
        val body = if (named.size == 1) named.first() else named.dropLast(1).joinToString(", ") + " and " + named.last()
        return "the $body ${if (named.size == 1) "scope" else "scopes"}"
    }

    /**
     * Every name Slack lets a person be addressed by, because a field somebody
     * types into is filled in from whichever one they were looking at.
     */
    private fun User.answersTo(name: String): Boolean = sequenceOf(
        this.name,
        realName,
        profile?.displayName,
        profile?.displayNameNormalized,
        profile?.realName,
        profile?.realNameNormalized,
    ).any { it != null && it.equals(name, ignoreCase = true) }

    /**
     * The second line of a suggestion, and null when there would not be one:
     * a name that only repeats the handle is a row twice as tall saying half
     * as much.
     */
    private fun User.ownName(handle: String): String? = sequenceOf(
        profile?.displayName,
        realName,
        profile?.realName,
    ).firstOrNull { !it.isNullOrBlank() && !it.equals(handle, ignoreCase = true) }

    private fun found(user: User): SlackTargetCheck {
        val label = user.profile?.displayName?.ifBlank { null }
            ?: user.realName?.ifBlank { null }
            ?: user.name
        return found(SlackTargetKind.USER, user.id, label, label)
    }

    /**
     * Slack answered something other than an answer.
     *
     * The one that matters is `missing_scope`; the rest are here so that none of
     * them is quietly turned into "it does not exist", which is the only way
     * this feature can do harm.
     */
    private fun refusal(error: String?, scopes: List<String>): SlackTargetCheck = when (error) {
        MISSING_SCOPE, NOT_ALLOWED_TOKEN_TYPE -> missingScope(scopes)
        RATELIMITED -> unchecked("Not checked - Slack is rate-limiting this connection. Try again shortly.")
        INVALID_AUTH, TOKEN_REVOKED, ACCOUNT_INACTIVE -> unchecked(
            "Not checked - Slack refused this connection's bot token ($error).",
        )
        else -> unchecked("Not checked - Slack answered ${error ?: "nothing usable"}.")
    }

    /**
     * The sentence this whole feature is built around, and the one the picker
     * reuses unchanged.
     *
     * Two words and a scope, in that order. "Not checked" is what keeps this
     * apart from "does not exist", which is the distinction the feature exists
     * for; the scope is what somebody does about it. Everything else that was
     * once here - that the connection is fine, that sending needs no such scope,
     * that the app has to be reinstalled - was true and was eight lines in a box
     * with room for one.
     */
    private fun missingScope(scopes: List<String>): SlackTargetCheck =
        unchecked("Not checked - this connection's bot token is missing ${scopeList(scopes)}.")

    private fun found(kind: SlackTargetKind, id: String?, label: String, message: String) =
        SlackTargetCheck(SlackTargetOutcome.FOUND, message, id, label, kind)

    private fun notFound(message: String) = SlackTargetCheck(SlackTargetOutcome.NOT_FOUND, message)

    private fun unchecked(message: String) = SlackTargetCheck(SlackTargetOutcome.UNCHECKED, message)

    private fun none(message: String) = SlackSuggestions(SlackTargetOutcome.UNCHECKED, message)

    private companion object {
        const val APP_TOKEN_PREFIX = "xapp-"

        /**
         * One page, and the largest either endpoint takes. Not a page size so
         * much as the size of the only page there is: what does not fit is
         * reported as unchecked rather than paged through. [suggest] pages
         * instead, and uses [READ_PAGE].
         */
        const val PAGE = 1000

        /**
         * What one page of a read for suggestions asks for.
         *
         * Smaller than [PAGE] on Slack's own advice: `conversations.list` times
         * out on large pages often enough that the documentation asks for no
         * more than a couple of hundred, and a timed-out page is a whole read
         * thrown away where a small one is a retry nobody notices.
         */
        const val READ_PAGE = 200

        /**
         * The cap that makes a read end. [READ_PAGE] times this is the most
         * entries one connection's list can hold, and with [SlackListingCache]'s
         * own cap it is the whole of what this feature can occupy: two thousand
         * rows of three short strings, thirty-two lists at a time.
         *
         * A Slack larger than that is not ruled on. It suggests from what was
         * read and says the list is partial, which is what somebody typing can
         * act on - the alternative is paging through fifty thousand members
         * while they wait.
         */
        const val MOST_PAGES = 10

        /**
         * How many matches come back. A picker shows a handful and a person
         * narrows it by typing; a list longer than this is scrolled rather than
         * read, and it is a lot of JSON to send on every keystroke.
         *
         * One cap for the merged list rather than one each, because it is the
         * length of what a person reads and not a quota to be shared out.
         */
        const val MOST_SHOWN = 25

        /** Enough of what was typed to recognise it, in a line that has to fit. */
        const val TYPED_SHOWN = 30

        const val EXACTLY = 0
        const val FROM_THE_START = 1
        const val SOMEWHERE = 2
        const val NOT_AT_ALL = 3

        const val CHANNELS_FIRST = 0
        const val PEOPLE_AFTER = 1

        /**
         * A Slack id is nine characters or more and is written in upper case, so
         * a channel name - which Slack keeps lower case - cannot be mistaken for
         * one. Kept strict for that reason: a name read as an id would be looked
         * up by `conversations.info`, come back `channel_not_found`, and be
         * reported as a channel that does not exist.
         */
        val CHANNEL_ID = Regex("""[CGD][A-Z0-9]{8,}""")
        val USER_ID = Regex("""[UW][A-Z0-9]{8,}""")

        /** Enough to tell an address from a handle, and no more than that. */
        val EMAIL = Regex("""[^@\s]+@[^@\s]+\.[^@\s]+""")

        val CHANNEL_SCOPES = listOf("channels:read", "groups:read")
        val USER_SCOPES = listOf("users:read")
        val EMAIL_SCOPES = listOf("users:read", "users:read.email")

        const val MISSING_SCOPE = "missing_scope"
        const val NOT_ALLOWED_TOKEN_TYPE = "not_allowed_token_type"
        const val RATELIMITED = "ratelimited"
        const val INVALID_AUTH = "invalid_auth"
        const val TOKEN_REVOKED = "token_revoked"
        const val ACCOUNT_INACTIVE = "account_inactive"
        const val CHANNEL_NOT_FOUND = "channel_not_found"
        const val USER_NOT_FOUND = "user_not_found"
        const val USERS_NOT_FOUND = "users_not_found"

        /** The two Slack says with when the token is short of a scope, and the only two a scope may be named for. */
        val SCOPE_REFUSALS = setOf(MISSING_SCOPE, NOT_ALLOWED_TOKEN_TYPE)

        /** Not one of Slack's, and never shown: a read that never reached it. */
        const val NOT_REACHED = "not_reached"

        /** Not one of Slack's either: a read somebody else started and this caller stopped waiting for. */
        const val BEING_READ = "being_read"
    }
}
