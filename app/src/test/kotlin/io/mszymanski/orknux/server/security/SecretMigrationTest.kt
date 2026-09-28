package io.mszymanski.orknux.server.security

import io.mszymanski.orknux.connector.connection.McpServer
import io.mszymanski.orknux.connector.connection.McpServerRepository
import io.mszymanski.orknux.connector.security.SecretCipher
import io.mszymanski.orknux.connector.security.SecretColumns
import io.mszymanski.orknux.connector.security.SecretConverter
import io.mszymanski.orknux.connector.security.SecretMigration
import io.mszymanski.orknux.connector.shell.Shell
import io.mszymanski.orknux.connector.shell.ShellRepository
import io.mszymanski.orknux.server.variable.VariableCatalog
import io.mszymanski.orknux.server.variable.VariableCatalogRepository
import io.mszymanski.orknux.server.variable.WorkspaceVariable
import io.mszymanski.orknux.server.variable.WorkspaceVariableRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager

/**
 * The one thing that runs on every boot and has never had a test.
 *
 * [SecretMigration] rewrites credentials that predate encryption. It is an
 * `ApplicationReadyEvent` listener, so whatever it throws is thrown at the
 * moment the application says it is up — and Spring Boot answers a listener on
 * that event by closing the context and failing the run. There is no screen for
 * that and no log line a person would go looking for: the container simply does
 * not come up.
 *
 * Several tests below put plaintext into a credential column with raw SQL rather
 * than through the entity. That is not a shortcut around the converter, it is
 * the case being tested: a row written before encryption existed is exactly a
 * row the converter never touched, and it is the only kind of row this class is
 * for.
 */
@SpringBootTest
class SecretMigrationTest(
    @Autowired val jdbc: JdbcTemplate,
    @Autowired val cipher: SecretCipher,
    @Autowired val columns: SecretColumns,
    @Autowired val transactions: PlatformTransactionManager,
    @Autowired val mcpServers: McpServerRepository,
    @Autowired val variables: WorkspaceVariableRepository,
    @Autowired val catalogs: VariableCatalogRepository,
    @Autowired val shells: ShellRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0
    private var catalogId: Long = 0
    private val shellNames = mutableListOf<String>()

    @BeforeEach
    fun seed() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "secret-migration")).id)
        catalogId = requireNotNull(
            catalogs.save(VariableCatalog(workspaceId = workspaceId, name = "credentials")).id,
        )
    }

    /**
     * Nothing may be left behind: a plaintext credential in one of these columns
     * is a landmine for the next context this suite builds, which runs this very
     * migration as it starts — and for the doctor, which now reports one.
     */
    @AfterEach
    fun clear() {
        variables.findAll().filter { it.workspaceId == workspaceId }.forEach(variables::delete)
        catalogs.findByWorkspaceIdOrderByNameAsc(workspaceId).forEach(catalogs::delete)
        mcpServers.findAll().filter { it.workspaceId == workspaceId }.forEach(mcpServers::delete)
        shells.findAll().filter { it.name in shellNames }.forEach(shells::delete)
        shellNames.clear()
        workspaces.findById(workspaceId).ifPresent(workspaces::delete)
    }

    /**
     * The installation that upgrades without setting the key.
     *
     * `SecretCipher.decrypt` was deliberately written never to throw, and the
     * doctor's "Secret key" card exists to say `Missing` in words — both of
     * which are promises that a server with no key still starts and still tells
     * somebody what is wrong with it. This is the one place that broke them.
     * `encrypt` reaches a `by lazy` that calls `check`, so the first plaintext
     * row threw `IllegalStateException` out of an `ApplicationReadyEvent`
     * listener, and Spring Boot turned that into a failed start.
     *
     * The person upgrading saw a container that exits. The page built to tell
     * them their key is missing is on the server that will not start.
     *
     * So: it may write nothing, and it must not throw. What it may not do is
     * take the installation with it. The sweep now asks `cipher.keyStatus()`
     * before it touches anything and returns having written nothing, saying so
     * at WARN with the count and the command that fixes it — which is the same
     * answer the doctor gives, on a server that is running.
     */
    @Test
    fun `an installation with no key still starts, and keeps its credentials`() {
        val id = plaintextMcpSecret("a token written before any of this was encrypted")
        val keyless = SecretMigration(jdbc, SecretCipher(""), columns, transactions)

        assertThatCode { keyless.encryptStoredSecrets() }
            .describedAs("a missing key is a thing to report, not a reason the server cannot start")
            .doesNotThrowAnyException()

        // And it left the credential alone, so the boot that does have the key
        // still finds it to seal. Losing it would be worse than not sealing it.
        assertThat(storedMcpSecret(id))
            .describedAs("the credential is still there to be encrypted by a later boot")
            .isEqualTo("a token written before any of this was encrypted")
    }

    /** The same, for a key that is set but cannot be used. Not throwing is the whole claim. */
    @Test
    fun `a key that is not usable is reported, not thrown`() {
        val id = plaintextMcpSecret("still plain text")
        val wrongLength = SecretMigration(jdbc, SecretCipher("c2hvcnQ="), columns, transactions)

        assertThatCode { wrongLength.encryptStoredSecrets() }.doesNotThrowAnyException()

        assertThat(storedMcpSecret(id)).isEqualTo("still plain text")
    }

    /**
     * The columns the migration did not know about.
     *
     * A hand-kept `COLUMNS` listed four. Eight fields carry
     * `@Convert(SecretConverter)`: the four listed, plus `shell.private_key`,
     * `shell.key_passphrase`, `proxy_rule.password` and
     * `workspace_variable.value` — every one of them added after that list was
     * written, and the list's own comment said "a new one belongs here as well
     * as on its entity".
     *
     * What makes it worth a test rather than a note is who is not told. The
     * doctor's "Stored secrets" card counts values that are *in* an envelope, so
     * a column full of plaintext was not unreadable, it was invisible: the card
     * said "All N values readable with the configured key" and the operator
     * reads that as "my credentials are encrypted". An SSH private key sitting
     * in plain text in the database is the thing they were told they did not
     * have.
     *
     * `workspace_variable.value` is swept whole. The converter encrypts every
     * variable's value, secret-kind or not, so the column is uniform and half of
     * it sealed would be a column the doctor reports on for ever. The SELECT
     * excludes anything already in an envelope, so the cost is one pass on the
     * first boot after upgrade and nothing on every boot after it.
     */
    @Test
    fun `every column that stores a credential is one the migration rewrites`() {
        val id = plaintextVariable("-----BEGIN OPENSSH PRIVATE KEY-----")

        migration().encryptStoredSecrets()

        assertThat(cipher.isEncrypted(storedVariableValue(id)))
            .describedAs("workspace_variable.value carries @Convert(SecretConverter) and was not swept")
            .isTrue()
    }

    /** The headline of the audit: an SSH private key, as an upgrade leaves it. */
    @Test
    fun `a private key written before encryption is sealed by the sweep`() {
        val id = plaintextShell("-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaA==\n", "hunter2")

        migration().encryptStoredSecrets()

        assertThat(cipher.isEncrypted(storedShellColumn(id, "private_key"))).isTrue()
        assertThat(cipher.isEncrypted(storedShellColumn(id, "key_passphrase"))).isTrue()
        // Sealed, and still the key it was: an unreadable key is not a fix.
        assertThat(cipher.decrypt(storedShellColumn(id, "key_passphrase"))).isEqualTo("hunter2")
    }

    /**
     * Run twice, which is what every boot after the first one is.
     *
     * Encrypting an envelope again would be silent and unrecoverable-looking: the
     * value still reads as sealed, `canRead` still says yes, and what comes out is
     * the inner envelope rather than the credential. Two things stop it and both
     * are tested here at once — the SELECT does not match a row that is already
     * sealed, and `encrypt` hands back a value that is already in an envelope.
     */
    @Test
    fun `a row that is already encrypted is left exactly as it was`() {
        val id = plaintextVariable("a credential from before")

        migration().encryptStoredSecrets()
        val afterFirst = storedVariableValue(id)

        migration().encryptStoredSecrets()

        assertThat(storedVariableValue(id))
            .describedAs("the same bytes, not an envelope around an envelope")
            .isEqualTo(afterFirst)
        assertThat(cipher.decrypt(storedVariableValue(id)))
            .describedAs("and what comes back out is the credential, not ciphertext")
            .isEqualTo("a credential from before")
    }

    /**
     * One row that cannot be written costs only itself.
     *
     * The method was `@Transactional`, which made every column of every row one
     * transaction — the opposite of its own docstring, which says each row is one
     * update — so a single row that would not go back rolled back every row that
     * had. Here one value is long enough that its envelope will not fit the
     * column, which is a real way for this to fail on a real upgrade, and the
     * row beside it must still come out sealed.
     *
     * Asserted on both engines, which took a second way of refusing the write.
     * SQLite does not enforce a varchar length: the oversized envelope goes in
     * happily, no row fails, and the test proved nothing about the engine
     * `orknux-one` ships. [refuseWrites] makes the same row unwritable there
     * instead, so what the sweep meets is what it meets on Postgres — an UPDATE
     * that throws, inside the per-row transaction — and the containment is read
     * off the same two assertions.
     */
    @Test
    fun `a row that cannot be written does not roll back the ones that could`() {
        val tooLong = plaintextVariable("x".repeat(4000), name = "tooLong")
        val ordinary = plaintextVariable("a credential that fits", name = "ordinary")
        refuseWrites(tooLong)

        try {
            assertThatCode { migration().encryptStoredSecrets() }.doesNotThrowAnyException()

            assertThat(cipher.isEncrypted(storedVariableValue(ordinary)))
                .describedAs("the row that could be written is written, whatever happened to the other")
                .isTrue()
            assertThat(storedVariableValue(tooLong))
                .describedAs("and the one that could not is left readable rather than lost")
                .isEqualTo("x".repeat(4000))
        } finally {
            allowWrites()
        }
    }

    /**
     * The test that would have caught all four the day they were written.
     *
     * The sweep no longer keeps a list — [SecretColumns] reads the
     * `@Convert(SecretConverter)` fields off the entities, so the list and the
     * annotations cannot come to disagree by anybody forgetting one. That makes a
     * test comparing the sweep's list against a list derived the same way
     * vacuous, so this derives it a different way: it scans the classpath for
     * `@Entity`, which is Spring's own scanner rather than the JPA metamodel the
     * production code asks, and counts the annotated fields it finds.
     *
     * And it names the ten, because "ten of something" is not a review and
     * this list is the one an auditor wants to read.
     */
    @Test
    fun `the sweep covers every field the converter encrypts`() {
        assertThat(columns.all.map { it.toString() }).containsExactlyInAnyOrder(
            "mcp_server.secret",
            "model_provider.secret",
            // A plugin's secret parameter, typed in rather than pointed at a
            // variable, V263.
            "plugin_parameter.secret_value",
            "proxy_rule.password",
            "shell.key_passphrase",
            "shell.private_key",
            "workspace_connection.app_token",
            "workspace_connection.secret",
            // The Slack user token search runs on, V247.
            "workspace_connection.user_token",
            // A workspace's web search key, V315.
            "workspace_search.api_key",
            "workspace_variable.value",
        )

        assertThat(columns.all)
            .describedAs("another encrypted field anywhere on the classpath is another swept column")
            .hasSize(annotatedFieldsOnTheClasspath())
    }

    /**
     * The half a reflection-only test cannot check: that those names are real.
     *
     * The table and column are worked out from the same two annotations Hibernate
     * reads, and where an entity names neither, from a second implementation of
     * Spring Boot's naming rule. A second implementation of a rule can drift from
     * the first, and the way that failure shows up is a column silently never
     * swept — which is the bug this whole change is about. So the schema is
     * asked.
     */
    @Test
    fun `every column the sweep names exists in the database`() {
        // `information_schema` is Postgres'; SQLite keeps its schema in
        // `sqlite_master` and answers this question in a different language
        // entirely. What the question is for - a second implementation of the
        // naming rule drifting from Hibernate's - is answered on either engine,
        // and the schema the two are built from is held together by
        // SqliteSchemaTest's `validate`.
        assumeTrue(postgres, "Postgres only: information_schema is Postgres'")

        val missing = columns.all.filterNot { column ->
            val present = jdbc.queryForObject(
                "select count(*) from information_schema.columns where table_name = ? and column_name = ?",
                Int::class.java,
                column.table,
                column.column,
            )
            (present ?: 0) > 0
        }

        assertThat(missing).describedAs("named by the entities, absent from the schema").isEmpty()
    }

    private fun migration() = SecretMigration(jdbc, cipher, columns, transactions)

    /** Which engine this run is on, read where `TestDatabase` wrote it. */
    private val engine: String
        get() = System.getProperty("spring.datasource.url").orEmpty()

    private val postgres: Boolean get() = engine.startsWith("jdbc:postgresql")

    /**
     * Asked rather than inferred from [postgres] being false: a trigger in
     * SQLite's spelling handed to any other database is a test that fails on
     * something that has nothing to do with what it is testing.
     */
    private val sqlite: Boolean get() = engine.startsWith("jdbc:sqlite")

    /**
     * Makes one row refuse to be written, where the column's own length will not.
     *
     * Only on SQLite, and it is not a way of making the test pass: it is the
     * only way of running it at all. A varchar length is documentation there
     * rather than a constraint, so the row the test calls unwritable is written
     * without complaint and there is no failure to contain. A trigger refusing
     * that one id puts the failure back where the column would have put it on
     * Postgres — an UPDATE that throws — and leaves everything the sweep does
     * around it untouched.
     */
    private fun refuseWrites(id: Long) {
        if (!sqlite) return
        jdbc.execute(
            """
            CREATE TRIGGER $REFUSING BEFORE UPDATE ON workspace_variable
            WHEN NEW.id = $id
            BEGIN SELECT RAISE(ABORT, 'this row cannot be written'); END
            """.trimIndent(),
        )
    }

    /** And takes it away again: the file outlives the test, and so would the trigger. */
    private fun allowWrites() {
        if (!sqlite) return
        jdbc.execute("DROP TRIGGER IF EXISTS $REFUSING")
    }

    /** Every `@Convert(SecretConverter)` field there is, found without asking the metamodel. */
    private fun annotatedFieldsOnTheClasspath(): Int {
        val scanner = ClassPathScanningCandidateComponentProvider(false)
        scanner.addIncludeFilter(AnnotationTypeFilter(Entity::class.java))
        return scanner.findCandidateComponents("io.mszymanski.orknux")
            .mapNotNull { it.beanClassName }
            .map { Class.forName(it) }
            .sumOf { entity ->
                generateSequence(entity) { it.superclass }
                    .takeWhile { it != Any::class.java }
                    .flatMap { it.declaredFields.asSequence() }
                    .count { it.getAnnotation(Convert::class.java)?.converter == SecretConverter::class }
            }
    }

    /** A credential row as an upgrade finds it: written straight to the column. */
    private fun plaintextMcpSecret(plaintext: String): Long {
        val id = requireNotNull(
            mcpServers.save(
                McpServer(workspaceId = workspaceId, name = "Legacy", address = "https://mcp.example.com"),
            ).id,
        )
        jdbc.update("update mcp_server set secret = ? where id = ?", plaintext, id)
        return id
    }

    private fun plaintextVariable(plaintext: String, name: String = "deployKey"): Long {
        val id = requireNotNull(
            variables.save(
                WorkspaceVariable(workspaceId = workspaceId, catalogId = catalogId, name = name),
            ).id,
        )
        jdbc.update("update workspace_variable set value = ? where id = ?", plaintext, id)
        return id
    }

    private fun plaintextShell(privateKey: String, passphrase: String): Long {
        val name = "secret-migration-${System.nanoTime()}"
        shellNames += name
        val id = requireNotNull(
            shells.save(Shell(name = name, host = "build.internal", username = "deploy")).id,
        )
        jdbc.update(
            "update shell set private_key = ?, key_passphrase = ? where id = ?",
            privateKey,
            passphrase,
            id,
        )
        return id
    }

    /** Read as the column holds it: through the entity it would come back decrypted. */
    private fun storedMcpSecret(id: Long): String? =
        jdbc.queryForObject("select secret from mcp_server where id = ?", String::class.java, id)

    private fun storedVariableValue(id: Long): String? =
        jdbc.queryForObject("select value from workspace_variable where id = ?", String::class.java, id)

    private fun storedShellColumn(id: Long, column: String): String? =
        jdbc.queryForObject("select $column from shell where id = ?", String::class.java, id)

    private companion object {
        const val REFUSING = "secret_migration_refuses_the_row"
    }
}
