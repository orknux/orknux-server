package io.mszymanski.orknux.server.user

import io.mszymanski.orknux.connector.connection.MailDelivery
import io.mszymanski.orknux.connector.connection.MailMessage
import io.mszymanski.orknux.server.mail.InstallationMail
import io.mszymanski.orknux.server.security.SignInThrottle
import io.mszymanski.orknux.server.security.SignInThrottleProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.session.jdbc.JdbcIndexedSessionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.OffsetDateTime
import java.util.Base64
import java.util.HexFormat
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** How long a mailed link lives, and how hard anybody may ask for one. */
@ConfigurationProperties(prefix = "orknux.security.password-reset")
data class PasswordResetProperties(
    /**
     * How long a link works for.
     *
     * An hour, chosen from what the link actually is: a secret sitting in a
     * mailbox, in whatever the mail server kept, and in whatever a phone
     * synchronised it to. Every one of those can be read later by somebody who is
     * not the account's owner - a shared laptop, a mailbox restored from a
     * backup, a screen left open - so the interesting number is not how long a
     * person needs but how long the copy stays dangerous.
     *
     * An hour is long enough that a relay having a slow afternoon, a spam folder
     * checked at the second attempt, or a walk to another room does not waste the
     * link, and short enough that it is dead well before anybody goes reading old
     * mail. Ten minutes reads as security and mostly produces people asking for a
     * third link; a day is a working password left in an inbox.
     */
    val expiry: Duration = Duration.ofHours(1),

    /**
     * How often a link may be asked for, in the shape sign-in already uses.
     *
     * Tighter per address asked about than sign-in is per username, because
     * asking for a reset is something a person does once and an ordinary mistake
     * does not repeat: three is already generous for somebody who thinks the
     * first mail went astray. The per-caller allowance is sign-in's, for
     * sign-in's reason - an office behind one router is one address.
     */
    val throttle: SignInThrottleProperties = SignInThrottleProperties(perUsername = 3),
)

/** The pieces a password reset needs that are beans nowhere else. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PasswordResetProperties::class)
class PasswordResetConfig {

    /**
     * A second throttle, of the same class as sign-in's and counting separately.
     *
     * The same class because the rules are the same rules - a free allowance,
     * then a doubling pause, then a ceiling, and forgetting a caller who has gone
     * quiet - and a second implementation of that would be a second set of
     * corners to get wrong.
     *
     * A separate instance because sharing sign-in's counters would mean anybody
     * who can reach the forgotten-password form could put a colleague's username
     * into a pause on the sign-in page simply by asking about them repeatedly.
     * That is a lockout somebody else can trigger, which is the one thing the
     * sign-in throttle was built not to be.
     */
    @Bean
    fun passwordResetThrottle(properties: PasswordResetProperties): SignInThrottle =
        SignInThrottle(properties.throttle)

    /**
     * Where the mail is handed to the relay, off the request's thread.
     *
     * This is the timing half of not saying whether an account exists.
     * Everything else about the two answers is identical by construction - the
     * same status, the same sentence - but opening a session to a mail server
     * takes a measurable fraction of a second and not sending anything takes
     * none, so a caller with a stopwatch could read the difference off a
     * synchronous implementation however carefully the words were chosen.
     * Answering first and sending afterwards makes the request cost the same
     * either way.
     *
     * One thread, a short queue, and anything past it dropped. The queue is
     * bounded because a queue an anonymous caller can grow is somewhere to put
     * the heap; dropping is the right answer at that point rather than running
     * the send on the caller's thread, which would hand back exactly the timing
     * signal this exists to remove. A dropped mail costs somebody a second
     * attempt, and the throttle means nobody reaches this far by accident.
     */
    @Bean("passwordResetMailer")
    fun passwordResetMailer(): Executor = ThreadPoolExecutor(
        1,
        1,
        0,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(QUEUED_MAILS),
        { runnable -> Thread(runnable, "password-reset-mail").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardPolicy(),
    )

    private companion object {
        /** Far more than a real installation queues at once, and a bounded number. */
        const val QUEUED_MAILS = 100
    }
}

/**
 * Getting back in after forgetting a password.
 *
 * Two halves. Somebody types an address and is told a link is on its way; then
 * somebody follows the link and types a new password. What holds them together is
 * a secret that only ever existed in one mail.
 *
 * **The answer never says whether the address belongs to anybody.** [request]
 * returns nothing and cannot fail: an address with no account, an address
 * belonging to a directory user, an address belonging to an internal identity
 * with no password to reset, and an address about to receive a link all leave by
 * the same door. Anything else would turn this form into a way of finding out who
 * works here, which is worth knowing to whoever writes the next phishing mail.
 *
 * **Only an internal user who has a password already.** A directory or OIDC
 * user's password belongs to the provider, so there is nothing here to reset and
 * pretending otherwise would send somebody a link that changes nothing they can
 * sign in with. An internal identity that has never had a password is not an
 * account either - mailing it one would turn something that could only be
 * assigned work into something that can sign in, which is an administrator's
 * decision rather than a stranger's.
 */
@Service
class PasswordResetService(
    private val users: AppUserRepository,
    private val resets: PasswordResetRepository,
    private val encoder: PasswordEncoder,
    private val mail: InstallationMail,
    private val address: io.mszymanski.orknux.server.security.InstallationAddress,
    private val properties: PasswordResetProperties,
    private val sessions: JdbcIndexedSessionRepository,
    @Qualifier("passwordResetThrottle") private val throttle: SignInThrottle,
    @Qualifier("passwordResetMailer") private val mailer: Executor,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Somebody asked for a link.
     *
     * Returns nothing at all, deliberately: there is no outcome worth reporting
     * that would not also be an answer to "does this address have an account
     * here". The only thing the caller can be refused is asking too often, and
     * that refusal is the same whoever they asked about.
     *
     * [from] is the address the connection came from rather than anything the
     * caller wrote in a header, for the reason sign-in gives: a header an
     * attacker sets is a fresh identity per request, and a counter keyed on one
     * counts nothing.
     */
    @Transactional
    fun request(email: String, from: String) {
        val wanted = email.trim()
        val counted = wanted.lowercase()
        throttle.check(counted, from)
        /*
         * Counted whether or not there was anybody to write to. The sign-in
         * throttle counts failures because sign-in has a success worth clearing
         * the record for; here there is no such thing, and counting only the
         * requests that found somebody would leave an attacker free to ask about
         * addresses that do not exist as often as they liked - which is precisely
         * the question they came to ask.
         */
        throttle.failed(counted, from)

        val held = accountFor(wanted) ?: return
        val address = held.email ?: return

        /*
         * Any link already outstanding for this account stops working now.
         *
         * One live link at a time, so somebody who asked twice cannot be confused
         * about which mail to open, and so a link read out of a mailbox later is
         * already dead if a newer one has been asked for since.
         */
        resets.deleteByUserId(requireNotNull(held.id))

        val secret = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(TOKEN_BYTES).also(random::nextBytes))
        resets.save(
            PasswordReset(
                userId = requireNotNull(held.id),
                tokenHash = hash(secret),
                expiresAt = OffsetDateTime.now().plus(properties.expiry),
            ),
        )

        val link = link(secret)
        if (link == null) {
            log.warn("A password reset was asked for, but no base URL is configured to write a link to")
            return
        }
        if (!mail.configured) {
            log.warn("A password reset was asked for, but this installation has no mail server configured")
            return
        }

        val message = MailMessage(
            to = listOf(address),
            subject = "Reset your Orknux password",
            body = body(held, link),
        )
        // Off this thread; the mailer bean says why the answer must not wait.
        mailer.execute { deliver(held.username, message) }
    }

    /**
     * Somebody followed the link and typed a new password.
     *
     * The link is spent here, and so is every other one outstanding for the
     * account: a reset that worked is the end of every request that led to it,
     * and leaving a sibling alive would mean a second mail in the same inbox
     * still opening the account an hour later.
     *
     * Every session the account had ends too. Changing a password because it may
     * be known to somebody else is worth nothing while that somebody else is
     * still signed in on it, and sessions are rows in the database rather than
     * objects in this process, so they can be ended for a person who is not the
     * one asking.
     */
    @Transactional
    fun complete(token: String, password: String, from: String): String {
        /*
         * Counted by the caller's address, and by nothing else.
         *
         * A token is thirty-two random bytes and is not going to be guessed, so
         * this is not the defence - it is here so that somebody trying anyway is
         * spending minutes a go rather than whatever the network allows. The token
         * itself is not used as a key: the throttle keeps its keys in memory and
         * writes them into a log line, and a working reset token belongs in
         * neither.
         */
        throttle.check(from, from)

        val held = resets.findByTokenHash(hash(token.trim())) ?: throw refuse(from)
        if (!held.usable(OffsetDateTime.now())) throw refuse(from)

        val owner = users.findById(held.userId).orElse(null) ?: throw refuse(from)
        /*
         * The account may have changed kind, or lost its password, since the link
         * was written. The rule that decided whether to issue one, asked again at
         * the last moment rather than trusted from an hour ago.
         */
        if (owner.type != UserType.INTERNAL || !owner.hasPassword) throw refuse(from)
        /*
         * Not counted against the caller. They are holding a link this
         * installation issued and have mistyped their new password, which is the
         * one thing here that is not somebody guessing - the same distinction
         * sign-in makes when it declines to count an attempt that never reached a
         * password check.
         */
        if (password.length < SHORTEST_PASSWORD) throw PasswordTooShortException(SHORTEST_PASSWORD)

        /*
         * Spent by a conditional update rather than read, checked and written:
         * two requests carrying the same link at once both read it unused, and
         * both set a password. Only one of them changes the row. Issue #588.
         */
        if (resets.spend(requireNotNull(held.id), OffsetDateTime.now()) != 1) throw refuse(from)
        resets.findByUserId(held.userId).filter { it.id != held.id }.forEach(resets::delete)

        owner.passwordHash = encoder.encode(password)
        owner.lastModifiedAt = OffsetDateTime.now()
        owner.lastModifiedBy = owner.username
        users.save(owner)

        endSessions(owner.username)
        log.info("The password for {} was reset from a mailed link", owner.username)
        return owner.username
    }

    /** A token that was not one, written down against the caller before refusing. */
    private fun refuse(from: String): PasswordResetInvalidException {
        throttle.failed(from, from)
        return PasswordResetInvalidException()
    }

    private fun deliver(username: String, message: MailMessage) {
        when (val delivered = mail.send(message)) {
            is MailDelivery.Sent -> log.info("A password reset link was sent for {}", username)

            is MailDelivery.Refused ->
                log.warn("A password reset link for {} was refused: {}", username, delivered.reason)

            is MailDelivery.NotPossible ->
                log.warn("A password reset link for {} was not sent: {}", username, delivered.reason)
        }
    }

    /**
     * The one account this address may reset, or nothing.
     *
     * Nothing where the address belongs to nobody, to a user the provider owns,
     * or to an internal identity with no password to replace. Nothing, too, where
     * it belongs to more than one account: an address two accounts share has no
     * single "your password" to reset, and picking one of them would mail a
     * working link about an account the reader did not ask about.
     */
    private fun accountFor(email: String): AppUser? {
        if (email.isEmpty()) return null
        val found = users.findByEmail(email)
        if (found.size > 1) {
            log.warn("A password reset was asked for an address that {} accounts share", found.size)
            return null
        }
        return found.singleOrNull()?.takeIf { it.type == UserType.INTERNAL && it.hasPassword }
    }

    /**
     * Where the link points.
     *
     * Built from configuration and never from the request. The Host header is
     * written by whoever is calling, so a link built from it is a link an
     * attacker chooses the address of - and the one thing this mail contains is a
     * secret that opens an account, sent to somebody with every reason to trust
     * it. Null where nothing is configured, because a link to nowhere is worse
     * than no mail at all.
     */
    private fun link(secret: String): String? {
        val base = address.base().ifEmpty { return null }
        return "$base/reset-password?token=$secret"
    }

    private fun body(user: AppUser, link: String): String = """
        Somebody asked to reset the password for ${user.username}.

        Open this link to choose a new one:

        $link

        The link works once, and stops working ${properties.expiry.toMinutes()} minutes after this
        message was sent. Ask for another from the sign-in page if it has expired.

        If this was not you there is nothing to do. Your password has not changed,
        and it will not change unless the link above is used.
    """.trimIndent()

    /**
     * Every session this account has, gone.
     *
     * Found by principal name, which for an internal user is exactly the stored
     * username: sign-in builds the authentication from the row rather than from
     * what was typed, so there is no second spelling of it in the session table
     * to miss.
     */
    private fun endSessions(username: String) {
        val open = sessions.findByPrincipalName(username)
        open.keys.forEach(sessions::deleteById)
        if (open.isNotEmpty()) log.info("{} session(s) for {} were ended by a password reset", open.size, username)
    }

    private fun hash(secret: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret.toByteArray()))

    private companion object {
        val random = SecureRandom()

        /** As much randomness as an API token carries, for a secret that opens an account. */
        const val TOKEN_BYTES = 32
    }
}

/**
 * The link is not one, or not any more.
 *
 * One exception for four different reasons - never issued, already used, expired,
 * or issued for an account that has since stopped being one - because telling
 * them apart would tell whoever is holding a token they found something about the
 * account behind it.
 */
class PasswordResetInvalidException : RuntimeException(
    "That reset link is no longer valid. Ask for a new one from the sign-in page.",
)
