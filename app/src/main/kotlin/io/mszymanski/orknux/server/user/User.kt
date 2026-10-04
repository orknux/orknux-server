package io.mszymanski.orknux.server.user

import io.mszymanski.orknux.server.graphql.Refusal
import io.mszymanski.orknux.server.security.Role
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinTable
import jakarta.persistence.ManyToMany
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.OffsetDateTime

/** Where a user is true: at the identity provider, or here. */
enum class UserType {

    /** Made and managed in this application. An identity, not a login. */
    INTERNAL,

    /** Vouched for by LDAP or OIDC; recorded here when they sign in. */
    EXTERNAL,
}

/**
 * Somebody this installation knows.
 *
 * Until this existed a user was whatever the directory said at sign-in: a
 * username on a session and a name in the audit log, with nothing anywhere to
 * list, search, or assign. This is that list — the people things can be
 * assigned to and the names other screens resolve.
 *
 * What it is not is an account. Nothing here holds a credential and nothing
 * here signs anybody in; the front door still belongs to the provider. An
 * INTERNAL user is an identity this installation made up — someone to assign
 * an issue to, a name to show — and editing one changes what is shown, not
 * what anybody may do at sign-in.
 */
@Entity
@Table(name = "app_user")
class AppUser(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    /** How the provider — or the creator — spells them. Never changes case elsewhere. */
    @Column(nullable = false, length = 120)
    val username: String,

    @Column(name = "display_name", nullable = false, length = 200)
    var displayName: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val type: UserType,

    /**
     * The roles an internal user is assigned.
     *
     * For an external user this records what the provider said at their last
     * sign-in — readable, so the list can answer "who can administer", and
     * never written from the edit screen, because the provider would overwrite
     * it the next time they arrive.
     */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "app_user_role",
        joinColumns = [JoinColumn(name = "user_id")],
        inverseJoinColumns = [JoinColumn(name = "role_id")],
    )
    var roles: MutableSet<Role> = mutableSetOf(),

    /**
     * The hash of a password, for an internal user who has one.
     *
     * Null for everybody else, and for an internal user who is only ever
     * assigned things: an identity that cannot sign in is still a useful
     * identity. Never for an external user - their password belongs to the
     * directory that keeps it, and holding one here would make this a second
     * place to change it.
     */
    @Column(name = "password_hash", length = 100)
    var passwordHash: String? = null,

    /**
     * Where to write to them, when anywhere is known.
     *
     * Seeded from the provider at sign-in - LDAP's mail attribute, OIDC's email
     * claim - and nullable because a directory need not supply one and because
     * every row written before this column existed has none.
     */
    @Column(length = 320)
    var email: String? = null,

    /**
     * Whether the address above was typed here rather than inherited.
     *
     * Sign-in refreshes what the provider says, and somebody who has typed
     * their own address should not have it quietly replaced every time they
     * arrive. A flag rather than a second column holding the provider's value:
     * the only question ever asked is whether sign-in may overwrite this, and a
     * shadow copy nobody reads would be a second answer to "what is their
     * address". Clearing a chosen address puts this back to false, so the next
     * sign-in seeds it again.
     */
    @Column(name = "email_chosen", nullable = false)
    var emailChosen: Boolean = false,

    /**
     * Whether the news the bell rings is also posted to [email].
     *
     * The one preference, rather than one per kind of news: who hears about an
     * issue is settled once, by the news desk, and a per-kind switch here would
     * be a second place deciding audience. This says only whether what was
     * already decided reaches an inbox as well.
     *
     * True by default. Nothing is sent at all on an installation with no mail
     * server configured, so the operator's switch is already the one that
     * protects somebody who has not asked for this; defaulting it off here would
     * only mean a feature nobody has until they find the Preferences page.
     */
    @Column(name = "email_notifications", nullable = false)
    var emailNotifications: Boolean = true,

    /**
     * Whether a chat says what an answer cost as well as how long it took.
     *
     * Here rather than on the workspace because a chat is one person's - see
     * `ChatOwnership` - so this decides only what the person who owns it reads.
     * Here rather than in the browser's storage because it follows somebody to
     * the next machine, which is the line the interface draws between the two.
     *
     * Off by default. The number is for somebody who has gone looking for it;
     * printed under every answer unasked it is a running total nobody wanted.
     */
    @Column(name = "chat_cost_shown", nullable = false)
    var chatCostShown: Boolean = false,

    /**
     * Which language they read the product in, or null while they have not said.
     *
     * A property of the person rather than of the workspace they are looking at
     * or the machine they are sitting at: a workspace is shared and a browser is
     * borrowed, and neither is a thing anybody chose. This is, and it follows
     * them.
     *
     * Null is not "English". It means nobody has chosen, which is what lets the
     * browser open in the language it is already set to; once somebody says
     * English, this holds `en` and a Polish browser leaves them in English. A
     * plain tag rather than an enum, because the set grows by a catalogue file
     * and not by a migration - what refuses an unknown tag is the interface,
     * which has no words for it and falls back.
     */
    @Column(length = 16)
    var language: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "last_modified_at", nullable = false)
    var lastModifiedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "last_modified_by", nullable = false, length = 120)
    var lastModifiedBy: String = "system",
) {

    /** Only what this installation made up is this installation's to change. */
    val editable: Boolean
        get() = type == UserType.INTERNAL

    /** Whether they can sign in at all, which is not the same as existing. */
    val hasPassword: Boolean
        get() = passwordHash != null
}

/**
 * A token: the same person by a different door.
 *
 * It carries a user and takes their roles, so what it may do is what they may
 * do - nothing here is a second permission system. Only the hash is kept: the
 * secret is shown once when it is made, and a table that could give it back
 * would be a password written down.
 */
@Entity
@Table(name = "app_user_token")
class AppUserToken(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    /** What it is for, in the words of whoever made it. */
    @Column(nullable = false, length = 120)
    var name: String,

    @Column(name = "token_hash", nullable = false, length = 64)
    val tokenHash: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    /** When it was last accepted, so an unused one can be found and removed. */
    @Column(name = "last_used_at")
    var lastUsedAt: OffsetDateTime? = null,
)

interface AppUserTokenRepository : JpaRepository<AppUserToken, Long> {

    fun findByTokenHash(tokenHash: String): AppUserToken?

    fun findByUserId(userId: Long): List<AppUserToken>
}

interface AppUserRepository : JpaRepository<AppUser, Long> {

    /** However the name was typed: one person per name is the table's own rule. */
    @Query("select u from AppUser u where lower(u.username) = lower(:username)")
    fun findByUsername(username: String): AppUser?

    /** The list, filtered the way the search box asks: by name, either of them. */
    @Query(
        "select u from AppUser u where lower(u.username) like lower(concat('%', :search, '%')) " +
            "or lower(u.displayName) like lower(concat('%', :search, '%')) order by lower(u.displayName)",
    )
    fun search(search: String): List<AppUser>

    /**
     * Whoever has this address, without regard to case.
     *
     * A list rather than one, because nothing stops two accounts recording the
     * same address - a shared mailbox, or somebody with a second identity for an
     * assistant. What to do about that is the caller's decision and not the
     * table's.
     */
    @Query("select u from AppUser u where lower(u.email) = lower(:email)")
    fun findByEmail(email: String): List<AppUser>
}

/**
 * Short enough not to be a fight, long enough to be worth having.
 *
 * A length and nothing else: composition rules push people towards worse
 * passwords they can remember rather than better ones they cannot.
 *
 * Here rather than beside whichever screen asks for it, because three things now
 * set a password - an administrator, the owner, and a mailed reset link - and a
 * minimum that differed between them would be a minimum that meant nothing.
 */
const val SHORTEST_PASSWORD = 12

class UserNotFoundException(val id: Long) : RuntimeException("No user with id $id"), Refusal {

    override val arguments get() = mapOf("id" to id)
}

class UserNameTakenException(val username: String) :
    RuntimeException("A user named \"$username\" already exists"), Refusal {

    override val arguments get() = mapOf("username" to username)
}

class UserNameInvalidException : RuntimeException("A user needs a username")

class PasswordTooShortException(val shortest: Int) :
    RuntimeException("A password needs at least $shortest characters"), Refusal {

    override val arguments get() = mapOf("shortest" to shortest)
}

class PasswordWrongException : RuntimeException("That is not the current password")

/**
 * Something that is not an address was offered as one.
 *
 * Checked barely: a name, an at sign, and a domain with a dot in it. Anything
 * stricter starts refusing addresses that work - a plus in the name, a long
 * suffix, a host nobody has heard of - and an installation that will not accept
 * somebody's real address is worse than one that accepts an odd-looking one.
 */
class EmailInvalidException(val email: String) :
    RuntimeException("\"$email\" does not look like an email address"), Refusal {

    override val arguments get() = mapOf("email" to email)
}

/**
 * Somebody tried to give a password to a user the directory owns.
 *
 * Not a permission that can be granted: the provider is where they are true,
 * and a password here would be a second one to forget.
 */
class PasswordNotSettableException(val username: String) : RuntimeException(
    "\"$username\" signs in through the identity provider, so there is no password to set here",
), Refusal {

    override val arguments get() = mapOf("username" to username)
}

/**
 * Somebody asked for an access token for a user the identity provider owns.
 *
 * A token would outlive whatever the provider decides about them - disabled
 * there, still signing in here - which is the one thing an installation must
 * not let a token do.
 */
class TokenNotIssuableException(val username: String) : RuntimeException(
    "\"$username\" signs in through the identity provider, so no access token can be made for them here",
), Refusal {

    override val arguments get() = mapOf("username" to username)
}

class TokenNotFoundException(val id: Long) : RuntimeException("No token with id $id"), Refusal {

    override val arguments get() = mapOf("id" to id)
}

/**
 * Somebody tried to edit an external user.
 *
 * Says why rather than only refusing: the provider is where an external user is
 * true, and an edit here would hold until their next sign-in and then silently
 * lose.
 */
class UserExternallyManagedException(val username: String) : RuntimeException(
    "\"$username\" comes from the identity provider and cannot be edited here. " +
        "What the provider says about them overwrites this at their next sign-in.",
), Refusal {

    override val arguments get() = mapOf("username" to username)
}

