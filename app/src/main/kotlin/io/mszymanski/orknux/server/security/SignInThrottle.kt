package io.mszymanski.orknux.server.security

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * How hard somebody may try to sign in.
 *
 * The defaults are chosen from the two people they have to serve at once. A
 * person who has forgotten which of their passwords this one is gets five tries
 * with nothing in the way, and a two second pause on the sixth - short enough
 * that they will not notice they were made to wait. Somebody working through a
 * word list is at five minutes a try within a dozen attempts, which is a
 * different activity entirely.
 */
@ConfigurationProperties(prefix = "orknux.security.sign-in")
data class SignInThrottleProperties(
    /** Tries against one username that cost nothing; the next one waits. */
    val perUsername: Int = 5,

    /**
     * Tries from one address that cost nothing; the next one waits.
     *
     * Higher, because an address is not a person: an office behind one router is
     * one address, and a limit sized for a person would be spent by a Monday
     * morning.
     */
    val perAddress: Int = 20,

    /** The pause on the first failure past the allowance; it doubles after that. */
    val firstWait: Duration = Duration.ofSeconds(2),

    /**
     * Where the doubling stops.
     *
     * There is a ceiling because there has to be an end: a wait that kept
     * doubling would become a lockout, and a lockout somebody else can trigger
     * by guessing at your username is a way of taking you off the system rather
     * than a way of protecting you.
     */
    val longestWait: Duration = Duration.ofMinutes(5),

    /**
     * How long a quiet username or address is remembered for.
     *
     * Nothing here is permanent. Stop failing for this long and the record is
     * dropped, so a bad afternoon does not follow anybody into the next day.
     */
    val forgetAfter: Duration = Duration.ofMinutes(15),
)

/**
 * Slowing down a caller who keeps getting it wrong.
 *
 * `POST /api/session` is the one door anybody may knock on, and until this it
 * counted nothing. A twelve character minimum and an unknown username that
 * returns before bcrypt runs meant it was not a free way to burn the processor,
 * but a username somebody knows exists - and in an internal tracker most of them
 * are - could be tried at whatever rate the network allowed, and under LDAP
 * every one of those tries landed on the directory as well.
 *
 * **Both a username and an address**, because either alone is half a rule. Per
 * username only, and one machine works through a list of names and is never
 * slowed at all. Per address only, and a botnet spreads a single username across
 * a thousand of them.
 *
 * **Backoff rather than lockout.** A wait doubles and then stops, and a record
 * that has been quiet is forgotten, so there is no state anybody can put you in
 * that you cannot get out of by waiting. That is not softness: an account that
 * locks is an account a stranger can close by guessing at it badly on purpose.
 *
 * **A refused attempt is not counted.** It never reached a password check, so
 * counting it would let somebody hold a colleague's username at the ceiling
 * indefinitely simply by continuing to knock - which is the lockout this is
 * built to avoid, arrived at by a longer road.
 *
 * **In this process, in memory.** No datastore and no dependency: the state is
 * a few counters, it is worth nothing after a restart, and an installation
 * running two instances gets two counters that are each strict enough. Buying a
 * shared one would cost an operator a service to run for a benefit they cannot
 * see.
 *
 * **Primary, because there is more than one of these.** The class is instantiated
 * a second time for the forgotten-password form, which needs the same rules and
 * must not share the counters - otherwise anybody able to reach that form could
 * put a colleague's username into a pause here. This one is sign-in's, and
 * anything that wants the other asks for it by name.
 */
@Component
@Primary
@EnableConfigurationProperties(SignInThrottleProperties::class)
class SignInThrottle(private val properties: SignInThrottleProperties) {

    /** What has gone wrong for one username or one address, and until when. */
    private data class Attempts(val failures: Int, val last: Long, val until: Long)

    private val byUsername = ConcurrentHashMap<String, Attempts>()
    private val byAddress = ConcurrentHashMap<String, Attempts>()

    /**
     * Refuses if this caller is in a pause, and does nothing otherwise.
     *
     * The longer of the two pauses wins, so a slowed address is not let through
     * by a fresh username or the other way about.
     */
    fun check(username: String, address: String) {
        val now = System.currentTimeMillis()
        val wait = maxOf(waitOn(byUsername, key(username), now), waitOn(byAddress, address, now))
        if (wait > 0) {
            log.info("A sign-in as {} from {} was asked to wait {}ms", username, address, wait)
            throw TooManySignInAttempts(Duration.ofMillis(wait))
        }
    }

    /** The password was wrong, or there was nobody to check it against. */
    fun failed(username: String, address: String) {
        val now = System.currentTimeMillis()
        record(byUsername, key(username), properties.perUsername, now)
        record(byAddress, address, properties.perAddress, now)
    }

    /**
     * Somebody got in, so both records are cleared.
     *
     * The address as well as the username: behind one router a colleague's
     * fumbling would otherwise be spent on you, and against somebody who holds
     * a working credential already the address count was never the defence.
     */
    fun succeeded(username: String, address: String) {
        byUsername.remove(key(username))
        byAddress.remove(address)
    }

    private fun waitOn(records: ConcurrentHashMap<String, Attempts>, key: String, now: Long): Long {
        val held = records[key] ?: return 0
        if (now - held.last > properties.forgetAfter.toMillis()) {
            records.remove(key, held)
            return 0
        }
        return (held.until - now).coerceAtLeast(0)
    }

    private fun record(records: ConcurrentHashMap<String, Attempts>, key: String, allowance: Int, now: Long) {
        forget(records, now)
        records.compute(key) { _, held ->
            val counted = held?.takeIf { now - it.last <= properties.forgetAfter.toMillis() }
            val failures = (counted?.failures ?: 0) + 1
            Attempts(failures, now, now + waitAfter(failures, allowance))
        }
    }

    /**
     * Nothing at all while the allowance lasts, then a doubling wait up to the
     * ceiling.
     *
     * The allowance is counted in attempts rather than in what is left after
     * them: five means five tries that cost nothing and a wait on the sixth,
     * which is what somebody reading the setting expects it to mean.
     */
    private fun waitAfter(failures: Int, allowance: Int): Long {
        val past = failures - allowance + 1
        if (past <= 0) return 0
        val doubled = properties.firstWait.toMillis() shl minOf(past - 1, DOUBLINGS)
        return minOf(doubled, properties.longestWait.toMillis())
    }

    /**
     * Keeps the counters from becoming the thing they defend against.
     *
     * A caller who sprays a new username at every attempt would otherwise be
     * writing a row of memory per try, and a defence that fills the heap has
     * chosen the wrong enemy. Quiet records go first; if there are still too
     * many after that, the ones that are not holding anybody in a pause go,
     * and only then the oldest of the rest.
     *
     * It used to drop the lot. That made the defence a switch an attacker could
     * press: spray ten thousand names, and the record pausing the one name being
     * guessed was wiped with them. A record in a pause is what this exists for,
     * so it is the last thing let go. Issue #588.
     */
    private fun forget(records: ConcurrentHashMap<String, Attempts>, now: Long) {
        if (records.size < MOST_REMEMBERED) return
        records.values.removeIf { now - it.last > properties.forgetAfter.toMillis() }
        if (records.size < MOST_REMEMBERED) return
        log.warn("Sign-in attempts are being spread over more than {} names or addresses", MOST_REMEMBERED)
        records.values.removeIf { it.until <= now }
        val over = records.size - MOST_REMEMBERED / 2
        if (over > 0) {
            records.entries.sortedBy { it.value.last }.take(over).forEach { records.remove(it.key, it.value) }
        }
    }

    /** One bucket per person, not one per way of spelling them. */
    private fun key(username: String) = username.trim().lowercase()

    private companion object {
        val log = LoggerFactory.getLogger(SignInThrottle::class.java)

        /** Enough doublings to pass any sane ceiling, and few enough not to overflow. */
        const val DOUBLINGS = 20

        /** How many names and addresses are worth remembering at once. */
        const val MOST_REMEMBERED = 10_000
    }
}

/**
 * The answer to knocking too often: 429, and how long to leave it.
 *
 * Said plainly rather than disguised as a wrong password, because the person who
 * most often meets this is somebody who mistyped their own password twice and
 * deserves to know why the third attempt is being made to wait.
 */
class TooManySignInAttempts(private val wait: Duration) : ResponseStatusException(
    HttpStatus.TOO_MANY_REQUESTS,
    "Too many sign-in attempts. Try again in ${wait.toSeconds().coerceAtLeast(1)} seconds.",
) {

    override fun getHeaders(): HttpHeaders = HttpHeaders().apply {
        set(HttpHeaders.RETRY_AFTER, wait.toSeconds().coerceAtLeast(1).toString())
    }
}
