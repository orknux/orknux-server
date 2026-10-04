package io.mszymanski.orknux.server.admin

import io.mszymanski.orknux.connector.security.SecretCipher
import io.mszymanski.orknux.connector.security.SecretColumns
import io.mszymanski.orknux.server.attachment.AttachmentProperties
import io.mszymanski.orknux.server.database.isSqlite
import io.mszymanski.orknux.server.database.jdbcUrlOf
import io.mszymanski.orknux.server.security.AUTHENTICATION_OFF
import io.mszymanski.orknux.server.security.AuthMethod
import io.mszymanski.orknux.server.security.OPEN_ACCESS_USERNAME
import io.mszymanski.orknux.server.security.SecurityProperties
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.security.WebProperties
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Controller
import java.nio.file.Files
import java.nio.file.Path

/**
 * Whether this installation is configured correctly — which is a different question
 * from whether it can reach things.
 *
 * Monitoring answers "is the database up, is Temporal up". Everything can be up and
 * the installation still be broken: the encryption key is read on first use, so a
 * server with no key starts perfectly, reports itself healthy, and fails the first
 * time somebody saves a credential. That happened here, and it took forty minutes in
 * a stack trace to find, because nothing on any screen was in a position to say it.
 *
 * These are the checks that can be made before anybody trips over them. Each one
 * answers with a verdict and a sentence — not a code, not a boolean — because the
 * point is to be readable at the moment somebody is wondering what is wrong.
 */
@Controller
class DoctorAPI(
    private val cipher: SecretCipher,
    private val security: SecurityProperties,
    private val web: WebProperties,
    private val access: WorkspaceAccess,
    private val jdbc: JdbcTemplate,
    private val attachments: AttachmentProperties,
    private val secrets: SecretColumns,
    /** Which replica runs what must run once. Issue #597. */
    private val leader: io.mszymanski.orknux.connector.cluster.ClusterLeader =
        io.mszymanski.orknux.connector.cluster.ClusterLeader.alone(),
) {

    @QueryMapping
    fun doctor(): List<DoctorCheckView> {
        access.requireAdmin()
        return listOf(
            "Secret key" to ::secretKey,
            "Stored secrets" to ::storedSecrets,
            "Authentication" to ::authentication,
            "Attachments" to ::attachmentsLocation,
            "Schema" to ::schema,
            "Allowed origins" to ::origins,
            "Replicas" to ::replicas,
        ).map { (name, check) -> attempt(name, check) }
    }

    /**
     * One check's answer, or the fact that there is not one — never the other
     * checks' silence.
     *
     * A check that throws used to take the page with it, and the whole page is
     * one GraphQL field: one query against a catalogue table SQLite does not
     * have turned every one of these into a single INTERNAL_ERROR, on the image
     * that exists for people trying this for the first time. The page that is
     * supposed to say what is wrong was the one thing that could not.
     *
     * So each check is run inside its own catch and a failing one reports itself
     * as unanswered. That is worth more than the fix to the query underneath it:
     * a check added next year gets the same isolation without anybody
     * remembering to ask for it, and the worst a mistake in one can do is cost
     * its own card.
     */
    private fun attempt(name: String, check: () -> DoctorCheckView): DoctorCheckView =
        runCatching(check).getOrElse { failure ->
            warn(
                name,
                "Could not be checked on this installation: " +
                    (failure.message?.lines()?.first() ?: failure::class.simpleName) +
                    ". Every other check on this page still stands.",
            )
        }

    /**
     * The one that started this.
     *
     * Asked of the cipher rather than re-derived here, so the check and the thing it
     * checks cannot disagree about what a usable key is.
     */
    private fun secretKey(): DoctorCheckView = when (val status = cipher.keyStatus()) {
        SecretCipher.KeyStatus.Usable ->
            ok("Secret key", "Set, and the right length for AES-256.")

        SecretCipher.KeyStatus.Missing -> fail(
            "Secret key",
            "Not set — every credential write will fail, and stored ones cannot be read. " +
                "Set ORKNUX_SECRET_KEY; generate one with: openssl rand -base64 32",
        )

        SecretCipher.KeyStatus.NotBase64 -> fail(
            "Secret key",
            "Set, but not valid base64. Generate one with: openssl rand -base64 32",
        )

        is SecretCipher.KeyStatus.WrongLength -> fail(
            "Secret key",
            "Set, but decodes to ${status.bytes} bytes; AES-256 needs 32.",
        )
    }

    /**
     * Whether what is already stored can still be read.
     *
     * A key that is present is not the same as the key these values were written
     * with. Rotating one without re-encrypting leaves a server that starts, reports
     * a usable key, and cannot read a single secret it holds — the failure looks
     * like corruption rather than like configuration.
     *
     * Asked of the cipher, for the same reason the key is. This used to catch
     * around `decrypt` and treat a thrown failure as the finding — and `decrypt`
     * does not throw, by design, so the answer was "all readable" whatever the
     * key was. The one page somebody opens to ask whether their credentials are
     * in trouble said no in exactly the case it exists for. `canRead` is the
     * question the cipher can actually answer.
     */
    private fun storedSecrets(): DoctorCheckView {
        val stored = encryptedValues()
        val clear = plaintextValues()

        if (stored.isEmpty()) {
            if (clear.isEmpty()) return ok("Stored secrets", "None stored yet; nothing to read back.")
            return fail("Stored secrets", inTheClear(clear))
        }

        val usable = cipher.keyStatus() == SecretCipher.KeyStatus.Usable
        val unreadable = if (usable) stored.filterNot { cipher.canRead(it.ciphertext) } else stored

        if (unreadable.isEmpty()) {
            val readable = "All ${stored.size} values readable with the configured key"
            if (clear.isEmpty()) return ok("Stored secrets", "$readable, and none stored in the clear.")
            return fail("Stored secrets", "$readable. ${inTheClear(clear)}")
        }

        /*
         * Counted and named, because the two answer different halves of it. The count
         * is the size of the problem — one credential lost is an afternoon, all of
         * them is a restore — and the names are where to go next, which is what this
         * screen exists to save somebody a database query for. A column holding
         * several says so once, with how many: the seeded shells put the same column
         * on the list a dozen times over, which reads as a dozen problems.
         *
         * Never the value, which cannot be read anyway and must not be shown if it
         * could.
         */
        val named = unreadable.groupingBy { it.where }.eachCount()
            .entries.joinToString("; ") { (where, count) -> if (count == 1) where else "$where ($count)" }
        val why = if (usable) "— this is not the key they were written with" else "because the key above is not usable"
        val lost = "${unreadable.size} of ${stored.size} values cannot be read $why: $named. " +
            "They have to be entered again, or the original key restored."
        return fail("Stored secrets", if (clear.isEmpty()) lost else "$lost ${inTheClear(clear)}")
    }

    /**
     * Credentials sitting in the database as the text they are.
     *
     * The other half of this card, and the half it could not see. [encryptedValues]
     * finds a secret by its envelope, which is the only way to recognise one in a
     * column nobody named — and it is exactly why a value that was never encrypted
     * was not merely unreadable to this check but invisible to it. Four columns
     * were missing from the boot sweep for a year and the card said "All N values
     * readable with the configured key" the whole time, which an operator reads as
     * "my credentials are encrypted". An SSH private key in plain text was the one
     * thing they had been told they did not have.
     *
     * Plaintext cannot be found the way ciphertext can: a credential in the clear
     * looks like every other string in the database. So this is the one part of the
     * page that has to be told where to look, and it is told by the entities rather
     * than by a list — [SecretColumns] reads the `@Convert(SecretConverter)` fields,
     * the same annotation that decides a value is encrypted at all. A column this
     * cannot see is a column that is not encrypted anywhere, which is a different
     * bug and not a silent one.
     *
     * Counted rather than fetched. There is no reason for a plaintext credential to
     * be read into this process to be counted, and every reason not to.
     */
    private fun plaintextValues(): Map<String, Long> = secrets.all
        .associate { column ->
            val quoted = "\"${column.table}\".\"${column.column}\""
            val count = runCatching {
                jdbc.queryForObject(
                    "select count(*) from \"${column.table}\" " +
                        "where $quoted is not null and $quoted <> '' and $quoted not like ?",
                    Long::class.java,
                    "$ENVELOPE%",
                )
            }.getOrNull() ?: 0L
            column.toString() to count
        }
        .filterValues { it > 0 }

    /**
     * How a plaintext credential is put to the person reading the page.
     *
     * Named by column and counted, like the unreadable ones, and for the same
     * reason: the count is the size of it and the name is where to go. What is
     * different is the sentence after — an unreadable credential is a thing to
     * restore, and a readable one that should not be is a thing to fix now.
     */
    private fun inTheClear(clear: Map<String, Long>): String {
        val named = clear.entries.joinToString("; ") { (where, count) ->
            if (count == 1L) where else "$where ($count)"
        }
        val total = clear.values.sum()
        val subject = if (total == 1L) "1 credential is" else "$total credentials are"
        return "$subject stored in the clear, not encrypted at all: $named. " +
            "Anyone who can read this database or a backup of it can read them. They are encrypted on " +
            "the next start once a secret key is configured."
    }

    /**
     * Every encrypted value in the database, wherever it lives.
     *
     * Found rather than listed. Naming the tables meant this check knew about
     * variables and connections and silently ignored the next thing to store a
     * credential — and the next thing always comes. The envelope is what identifies
     * one: `SecretCipher` writes a prefix precisely so a value can be recognised on
     * sight, and that works as well from SQL as from Kotlin.
     *
     * The scan asks the schema which columns could hold text and then asks each one
     * whether it holds any. That is a couple of hundred cheap queries on a schema
     * this size, run when somebody opens a diagnostic page — not on a request path.
     */
    private fun encryptedValues(): List<EncryptedValue> =
        textColumns().flatMap { (table, column) ->
            /*
             * The names come from the catalogue rather than from anything a caller
             * sent, and they are quoted anyway: a table called `order` is a syntax
             * error unquoted, and habit is what keeps a query safe on the day one of
             * these is not from the catalogue.
             */
            val quoted = "\"$table\".\"$column\""
            runCatching {
                jdbc.query(
                    "select $quoted as secret from \"$table\" where $quoted like ? limit $PER_COLUMN",
                    { row, _ -> EncryptedValue("$table.$column", row.getString("secret")) },
                    "$ENVELOPE%",
                )
            }.getOrDefault(emptyList())
        }

    /**
     * Which columns could hold text, asked of whichever catalogue this database
     * keeps.
     *
     * There are two, and they are not two spellings of one query. Postgres has
     * `information_schema`; SQLite has no such table at all, so the query that
     * worked everywhere the tests run failed on the first line in the all-in-one
     * image — the one installation somebody meets before they have decided to
     * run a database. What SQLite offers instead is its own catalogue and
     * `pragma_table_info`, which answers the same question a table at a time.
     *
     * Which of the two is asked follows the rest of the application rather than
     * this file's own guess: [isSqlite] over the URL the pool was built from is
     * what chooses the dialect, the scheduler's SQL and the session store's
     * propagation, and one place deciding for all of them is what keeps them
     * from disagreeing.
     *
     * The two predicates say the same thing in each database's terms. Postgres
     * names the type, so the type is named. SQLite does not have types so much
     * as affinities, and the rule it applies itself is that a declaration
     * containing CHAR, CLOB or TEXT holds text — so that is the rule asked here,
     * and a column declared `varchar(4000)` or `text` is caught without this
     * having to list the spellings the baseline happens to use.
     */
    private fun textColumns(): List<Pair<String, String>> {
        val sql = if (isSqlite(jdbc.dataSource?.let { jdbcUrlOf(it) })) SQLITE_TEXT_COLUMNS else POSTGRES_TEXT_COLUMNS
        return jdbc.query(sql) { row, _ -> row.getString("table_name") to row.getString("column_name") }
    }

    /** One encrypted value and where it lives; never its contents. */
    private data class EncryptedValue(val where: String, val ciphertext: String)

    /** Whether the chosen way in is actually configured enough to work. */
    private fun authentication(): DoctorCheckView = when (security.authMethod) {
        AuthMethod.LDAP -> ok("Authentication", "Username and password, against the directory.")

        /*
         * Nothing to be misconfigured, and that is the finding rather than the
         * absence of one. Whoever reads this screen after wondering where the
         * directory went should be told there is not one, in as many words - the
         * monitoring screen has stopped drawing a card for it, and two surfaces
         * that say nothing about the same thing are two surfaces that disagree
         * with somebody's memory of yesterday.
         */
        AuthMethod.INTERNAL -> ok(
            "Authentication",
            "Username and password, against accounts this installation holds itself. " +
                "No directory and no provider are configured, and none is contacted.",
        )

        /*
         * The loudest thing this screen can say about an installation, and it is a
         * WARN rather than a FAIL on purpose: nothing here is going to break, which
         * is what FAIL promises. It is configured, it works, and it is almost
         * certainly not what a second person looking at this installation expects -
         * which is precisely what WARN is for.
         *
         * The Doctor is behind requireAdmin, so under this method everybody reaches
         * it. That is the point rather than a hole: the one screen an operator opens
         * to ask whether this installation is set up correctly must answer the
         * question they did not think to ask.
         */
        AuthMethod.NONE -> warn(
            "Authentication",
            "$AUTHENTICATION_OFF Everything here acts as \"$OPEN_ACCESS_USERNAME\", which administers. " +
                "Set ORKNUX_AUTH_METHOD to LDAP, OIDC or INTERNAL to ask people to sign in again.",
        )

        AuthMethod.OIDC -> {
            val missing = buildList {
                if (security.oidc.issuer.isBlank()) add("issuer")
                if (security.oidc.clientId.isBlank()) add("client id")
            }
            if (missing.isEmpty()) {
                ok("Authentication", "OIDC, against ${security.oidc.issuer}.")
            } else {
                fail("Authentication", "OIDC is selected but the ${missing.joinToString(" and ")} is not set.")
            }
        }

        /*
         * Who may sign in is the finding here, not whether the client is set up.
         *
         * An OAuth app authenticates every GitHub account there is, so "it is
         * configured" and "it is safe" are different questions under this method
         * in a way they are not under the others - and the one worth putting on
         * this screen is the second. The application refuses to start with
         * neither an organisation nor a list, so what this reports is which of
         * the two is holding the door.
         */
        AuthMethod.GITHUB -> {
            val missing = buildList {
                if (security.github.clientId.isBlank()) add("client id")
                if (security.github.clientSecret.isBlank()) add("client secret")
            }
            val organisation = security.github.organisation.trim()
            val allowed = security.github.allowedLogins.filter { it.isNotBlank() }
            when {
                missing.isNotEmpty() -> fail(
                    "Authentication",
                    "GitHub is selected but the ${missing.joinToString(" and ")} is not set.",
                )

                organisation.isNotBlank() -> ok(
                    "Authentication",
                    "GitHub, and only members of $organisation" +
                        if (allowed.isEmpty()) "." else " and ${allowed.size} named login(s).",
                )

                else -> ok(
                    "Authentication",
                    "GitHub, and only these ${allowed.size} login(s): ${allowed.joinToString(", ")}. " +
                        "No organisation is named, so this list is the whole of who may sign in.",
                )
            }
        }
    }

    /**
     * Whether files can actually be written where they are meant to go.
     *
     * A relative path resolves against the working directory, which is fine on a
     * development machine and wrong in a container — and is discovered on upload,
     * by whoever was trying to attach something.
     */
    private fun attachmentsLocation(): DoctorCheckView {
        if (!attachments.enabled) return ok("Attachments", "Turned off for this installation.")

        val path = Path.of(attachments.location).toAbsolutePath()
        return try {
            Files.createDirectories(path)
            if (Files.isWritable(path)) {
                val warning = if (Path.of(attachments.location).isAbsolute) null else "relative to the working directory"
                ok("Attachments", listOfNotNull("Writable at $path", warning).joinToString(", ") + ".")
            } else {
                fail("Attachments", "$path exists but cannot be written to.")
            }
        } catch (failure: Exception) {
            fail("Attachments", "$path could not be created: ${failure.message}")
        }
    }

    /**
     * Which server runs what must run once - the sweeps, the checks, the Slack
     * sockets - and whether it is this one. Issue #597.
     *
     * Asked on whichever replica answered, so the sentence says which that was:
     * somebody reading it behind a load balancer would otherwise not know.
     */
    private fun replicas(): DoctorCheckView {
        if (!leader.isEnabled()) {
            return ok("Replicas", "No cluster lease: this server takes itself to be the only one on its database.")
        }
        val holder = leader.holder()
        return when {
            leader.leads() -> ok(
                "Replicas",
                "This server (${leader.instance}) holds the cluster lease, so it runs the sweeps, the checks and the Slack sockets.",
            )
            holder != null -> ok(
                "Replicas",
                "$holder holds the cluster lease and runs the sweeps, the checks and the Slack sockets; this server " +
                    "(${leader.instance}) takes over if it stops.",
            )
            else -> warn(
                "Replicas",
                "Nobody holds the cluster lease right now, so no server is running the sweeps or the Slack sockets. " +
                    "It is taken again at the next renewal; if this stays, the database is refusing the lease.",
            )
        }
    }

    /** Whether the database is at the version this build expects. */
    private fun schema(): DoctorCheckView {
        val version = runCatching {
            jdbc.queryForObject(
                "select version from flyway_schema_history where success order by installed_rank desc limit 1",
                String::class.java,
            )
        }.getOrNull()

        val failed = runCatching {
            jdbc.queryForObject("select count(*) from flyway_schema_history where not success", Int::class.java)
        }.getOrDefault(0) ?: 0

        return when {
            version == null -> fail("Schema", "No migration history — this database has not been migrated.")
            failed > 0 -> fail("Schema", "At v$version, with $failed failed migration(s) recorded.")
            else -> ok("Schema", "At v$version, with nothing failed.")
        }
    }

    /**
     * Whether the browser is allowed to talk to this server.
     *
     * Wrong origins are a blank screen and a console error, which is a long way from
     * the setting that caused it.
     */
    private fun origins(): DoctorCheckView {
        val origins = web.allowedOrigins
        return when {
            origins.isEmpty() -> warn(
                "Allowed origins",
                "None set. That is right where the interface is served from this server, and a blank " +
                    "screen where it is not.",
            )

            origins.any { it.trim() == "*" } -> warn(
                "Allowed origins",
                "Set to *, which allows any site to call this server with the caller's cookies.",
            )

            else -> ok("Allowed origins", origins.joinToString(", "))
        }
    }

    private companion object {
        /** What SecretCipher stamps on everything it writes. */
        const val ENVELOPE = "orkx1:"

        /** Enough from one column to say the column is affected, without reading a table. */
        const val PER_COLUMN = 20

        /** Every text column of the schema this application owns. */
        val POSTGRES_TEXT_COLUMNS = """
            select table_name, column_name
            from information_schema.columns
            where table_schema = 'public'
              and data_type in ('text', 'character varying')
            order by table_name, column_name
        """.trimIndent()

        /**
         * The same, from SQLite's catalogue. `sqlite_master` lists the tables and
         * `pragma_table_info` opens each one up; the internal tables SQLite keeps
         * for itself are left out, as `information_schema` leaves out the schemas
         * that are not this application's.
         */
        val SQLITE_TEXT_COLUMNS = """
            select m.name as table_name, c.name as column_name
            from sqlite_master m
            join pragma_table_info(m.name) c
            where m.type = 'table'
              and m.name not like 'sqlite!_%' escape '!'
              and (
                instr(lower(c.type), 'char') > 0
                or instr(lower(c.type), 'clob') > 0
                or instr(lower(c.type), 'text') > 0
              )
            order by m.name, c.name
        """.trimIndent()
    }

    private fun ok(name: String, detail: String) = DoctorCheckView(name, DoctorVerdict.OK, detail)

    private fun warn(name: String, detail: String) = DoctorCheckView(name, DoctorVerdict.WARN, detail)

    private fun fail(name: String, detail: String) = DoctorCheckView(name, DoctorVerdict.FAIL, detail)
}

/** What a check concluded. */
enum class DoctorVerdict {

    OK,

    /** Works, but is probably not what was meant. */
    WARN,

    /** Something is going to fail, and it is worth fixing before it does. */
    FAIL,
}

data class DoctorCheckView(
    val name: String,
    val verdict: DoctorVerdict,
    /** One sentence: what is true, and what to do where something is not. */
    val detail: String,
)
