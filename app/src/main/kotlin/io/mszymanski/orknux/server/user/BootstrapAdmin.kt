package io.mszymanski.orknux.server.user

import io.mszymanski.orknux.server.security.Role
import io.mszymanski.orknux.server.security.RoleRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

/**
 * The first administrator, named in the environment.
 *
 * Empty means an installation that seeds nobody, which is what an installation
 * with a directory or an OIDC provider wants: the people are the provider's and
 * an account made here would be a second way in that nobody asked for.
 */
@ConfigurationProperties(prefix = "orknux.bootstrap-admin")
data class BootstrapAdminProperties(
    /** What they sign in as. Empty seeds nobody. */
    val username: String = "",

    /**
     * What they sign in with, once.
     *
     * A password in an environment variable is a compromise and is treated as
     * one everywhere it appears: it is readable by anything that can see this
     * process, it sits in whatever file the variables were written into, and it
     * is not somewhere to leave a working credential. It exists to get somebody
     * through the door the first time so they can change it from inside.
     */
    val password: String = "",
)

/**
 * Makes the administrator an empty installation cannot make for itself.
 *
 * Every other way an account comes into being needs somebody already signed in.
 * A user is created by an administrator, or written down when the directory
 * vouches for somebody at the door - so an installation with no directory and no
 * OIDC provider has no first step at all: there is nobody to create the
 * administrator who could create you. That is the whole of what this fixes, and
 * why it is configuration rather than a screen.
 *
 * Nothing else about it is special. The account is an ordinary INTERNAL user
 * with an ordinary password hash, holding the built-in administrator role the
 * Roles screen shows, and it signs in through the same door
 * [InternalAuthentication] already opens for internal users whatever the
 * configured authentication method is. Nothing was weakened to let it in.
 *
 * At [ApplicationReadyEvent] because the role it needs is created by a migration
 * and Flyway has to have run first. Boot's `FlywayMigrationInitializer` is an
 * `InitializingBean`, so the migrations run while the context is still
 * refreshing and every singleton that touches the database is made to depend on
 * it; this event is published after the refresh has finished, so the ordering is
 * a property of when the event fires rather than something to declare.
 */
@Component
@EnableConfigurationProperties(BootstrapAdminProperties::class)
class BootstrapAdmin(
    private val users: AppUserRepository,
    private val roles: RoleRepository,
    private val encoder: PasswordEncoder,
    private val properties: BootstrapAdminProperties,
    private val transactions: PlatformTransactionManager,
) : SmartInitializingSingleton {

    /*
     * Seeded once every singleton exists - the schema migrated, the role table
     * filled - and before the web server opens its port, which Spring starts
     * after this. It used to be done at ApplicationReadyEvent, after the port
     * was open: a sign-in arriving the moment the server first answered was
     * refused with 401 for the half second before the account existed, which is
     * what failed 0.9.9.10's image check twice and anything else that signs in
     * as soon as a fresh container answers.
     */
    override fun afterSingletonsInstantiated() {
        TransactionTemplate(transactions).executeWithoutResult { seedAdministrator() }
    }

    /**
     * Runs on every start and is expected to do nothing on almost all of them.
     *
     * An existing account of that name is left exactly as it is: not the
     * password, not the roles, not whether it can sign in at all. The variables
     * are usually still set on the tenth restart, long after somebody has
     * changed the password or taken the account's administrator role away
     * deliberately, and a startup that quietly put either back would be a way
     * for anyone who can edit a compose file to reset an account they do not own.
     * Seeding is therefore only ever a creation, and the log says when it
     * declined to be anything more.
     */
    @Transactional
    fun seedAdministrator() {
        val username = properties.username.trim()
        val password = properties.password

        if (username.isEmpty() && password.isEmpty()) return
        if (username.isEmpty() || password.isEmpty()) {
            log.error(
                "ORKNUX_BOOTSTRAP_ADMIN_USERNAME and ORKNUX_BOOTSTRAP_ADMIN_PASSWORD are read together and " +
                    "only one of them was set, so no administrator was seeded.",
            )
            return
        }

        /*
         * The same minimum every other way of setting a password holds to.
         * Refused rather than seeded, because the alternative is an account
         * created with a password the product itself would not accept: nobody
         * could set it again, the Doctor screen would be right to complain
         * about it, and the person who typed it would find that out at the one
         * moment they most need to be able to sign in.
         */
        if (password.length < SHORTEST_PASSWORD) {
            log.error(
                "The password in ORKNUX_BOOTSTRAP_ADMIN_PASSWORD is shorter than the {} characters this " +
                    "installation requires of any password, so no administrator was seeded. Set a longer one.",
                SHORTEST_PASSWORD,
            )
            return
        }

        users.findByUsername(username)?.let { held ->
            leaveAlone(held, password)
            return
        }

        val administrators = administratorRole()
        if (administrators == null) {
            log.error(
                "No built-in administrator role exists, so \"{}\" was not seeded. An account that could sign in " +
                    "but administer nothing would be worse than none, since it looks like the door opened.",
                username,
            )
            return
        }

        users.save(
            AppUser(
                username = username,
                displayName = username,
                type = UserType.INTERNAL,
                roles = mutableSetOf(administrators),
                passwordHash = encoder.encode(password),
                // Says on the Users screen where this account came from, which
                // "system" would not.
                lastModifiedBy = "bootstrap",
            ),
        )

        log.warn(
            "Created the administrator \"{}\" from ORKNUX_BOOTSTRAP_ADMIN_USERNAME and " +
                "ORKNUX_BOOTSTRAP_ADMIN_PASSWORD. That password is readable by anything that can see this " +
                "process's environment, so it is a way in and not a credential to keep: sign in, change it, " +
                "then unset both variables.",
            username,
        )
    }

    /**
     * Says that an account was found and nothing was done to it.
     *
     * Louder when the password is still the one in the environment, which is the
     * common way this is left half finished: somebody got in, made the
     * workspaces, and never went back to change it. That check is the only thing
     * the configured password is used for once the account exists, and it says
     * nothing an attacker does not already have - they would need the variable
     * to make the comparison at all.
     */
    private fun leaveAlone(held: AppUser, password: String) {
        val hash = held.passwordHash
        val unchanged = held.type == UserType.INTERNAL && hash != null && encoder.matches(password, hash)

        if (unchanged) {
            log.warn(
                "\"{}\" still signs in with the password from ORKNUX_BOOTSTRAP_ADMIN_PASSWORD. Change it under " +
                    "the account itself and unset the variable; nothing here will change it for you.",
                held.username,
            )
        } else {
            log.info(
                "\"{}\" already exists, so nothing was seeded and nothing about the account was touched.",
                held.username,
            )
        }
    }

    /**
     * The role the product itself calls the administrator, rather than a name
     * spelled again here. It is the one a migration marks built in and gives the
     * ADMIN scope to, and [Role.administers] is the same question every other
     * check asks.
     */
    private fun administratorRole(): Role? = roles.findByBuiltinTrue().firstOrNull { it.administers }

    private companion object {
        val log = LoggerFactory.getLogger(BootstrapAdmin::class.java)
    }
}
