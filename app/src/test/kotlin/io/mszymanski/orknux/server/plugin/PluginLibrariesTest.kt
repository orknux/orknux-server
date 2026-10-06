package io.mszymanski.orknux.server.plugin

import io.mszymanski.orknux.server.action.RunFunctionInput
import io.mszymanski.orknux.server.action.FunctionAPI
import io.mszymanski.orknux.server.action.FunctionArgumentInput
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.context.support.WithMockUser
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A plugin that ships libraries, from the archive to a running call.
 *
 * Four claims carry the feature, and each is a test: the files somebody was
 * never asked about do not load - the refusal is the list; a declaration and
 * an archive that disagree are refused as the lie one of them is; an accepted
 * bundle runs, its imports reaching the shipped code; and the set already
 * allowed is not re-asked on a re-upload.
 */
@SpringBootTest
@WithMockUser(username = "alice", roles = ["ADMINS"])
class PluginLibrariesTest(
    @Autowired val pluginSources: PluginSources,
    @Autowired val upload: PluginUploadAPI,
    @Autowired val plugins: PluginRepository,
    @Autowired val libraries: PluginLibraryRepository,
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val functionApi: FunctionAPI,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        plugins.deleteAll()
        functions.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
    }

    private val pluginSource = """
        import { greet } from './lib/format.js';

        export default class Zipped extends OrknuxPlugin {
          id() { return 'shipped'; }
          apiVersion() { return 1; }
          libraries() { return ['lib/format.js', 'lib/names.js']; }
          functions() {
            return [new OrknuxFunction({
              name: 'greet',
              params: [{ name: 'who', type: 'string' }],
              returnType: 'string',
              run: (who) => greet(who),
            })];
          }
        }
    """.trimIndent()

    private val format = "import { NAME } from './names.js';\nexport function greet(who) { return 'hello, ' + who + ' from ' + NAME; }"
    private val names = "export const NAME = 'the library';"

    private fun zipped(vararg entries: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((path, content) in entries) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun load(archive: ByteArray, accepting: String? = null) =
        upload.upload(MockMultipartFile("file", "shipped.zip", "application/zip", archive), null, accepting)

    @Test
    fun `the first load is refused with the list, and nothing is stored`() {
        val archive = zipped("plugin.js" to pluginSource, "lib/format.js" to format, "lib/names.js" to names)

        val failure = runCatching { load(archive) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(PluginAgreementNeededException::class.java)
        assertThat((failure as PluginAgreementNeededException).libraries)
            .containsExactly("lib/format.js", "lib/names.js")
        assertThat(plugins.findAll()).isEmpty()
    }

    @Test
    fun `an accepted bundle runs, its imports reaching the shipped code`() {
        val archive = zipped("plugin.js" to pluginSource, "lib/format.js" to format, "lib/names.js" to names)
        load(archive, accepting = "lib/format.js,lib/names.js")

        val stored = plugins.findByKey("shipped")!!
        assertThat(libraries.findByPluginIdOrderByPositionAsc(requireNotNull(stored.id)).map { it.path })
            .containsExactly("lib/format.js", "lib/names.js")

        val function = functions.findAll().single { it.name == "shipped_greet" }
        val run = functionApi.runFunction(
            RunFunctionInput(
                workspaceId = workspaceId,
                functionId = requireNotNull(function.id),
                arguments = listOf(FunctionArgumentInput(name = "who", json = "\"dana\"")),
            ),
        )

        assertThat(run.error).isNull()
        assertThat(run.returned).isEqualTo("\"hello, dana from the library\"")
    }

    /**
     * The declaration is what somebody allows, so it has to agree with what
     * arrived - in both directions, and each is refused by the rule that
     * catches it first. A file smuggled into the archive is caught as the
     * dead weight it is; a declared file that never arrived is caught as the
     * declaration's lie.
     */
    @Test
    fun `an archive and a declaration that disagree are refused as the lie one of them is`() {
        val smuggling = zipped(
            "plugin.js" to pluginSource,
            "lib/format.js" to format,
            "lib/names.js" to names,
            "lib/extra.js" to "export const x = 1;",
        )
        val smuggled = runCatching { load(smuggling, accepting = "lib/format.js,lib/names.js,lib/extra.js") }
            .exceptionOrNull()
        assertThat(smuggled).isInstanceOf(PluginContractException::class.java)
        assertThat(smuggled!!.message).contains("nothing imports lib/extra.js")

        val promising = zipped(
            "plugin.js" to pluginSource.replace(
                "['lib/format.js', 'lib/names.js']",
                "['lib/format.js', 'lib/names.js', 'lib/promised.js']",
            ),
            "lib/format.js" to format,
            "lib/names.js" to names,
        )
        val promised = runCatching { load(promising, accepting = "lib/format.js,lib/names.js,lib/promised.js") }
            .exceptionOrNull()
        assertThat(promised).isInstanceOf(PluginContractException::class.java)
        assertThat(promised!!.message).contains("declared and not shipped").contains("lib/promised.js")
    }

    /**
     * A plugin says what it is, and the row says it back.
     *
     * The name used to come off the filename, which is a fact about how
     * somebody saved a file — so a zip called `slack (2).zip` produced a
     * plugin called `slack (2)`. A manifest is the plugin's own account of
     * itself: what it is called, what it is for, who wrote it, what version
     * it calls itself, and the face beside it.
     */
    @Test
    fun `a manifest beside the plugin says what it is, and the row keeps it`() {
        val archive = zipped(
            "plugin.js" to pluginSource,
            "lib/format.js" to format,
            "lib/names.js" to names,
            "plugin.json" to """
                {"key":"shipped","name":"Shipped Greeter","summary":"Greets, from a library.",
                 "author":"Somebody","version":"2.1.0","icon":"icon.svg"}
            """.trimIndent(),
            "icon.svg" to """<svg xmlns="http://www.w3.org/2000/svg" id="shipped-face"/>""",
        )

        load(archive, accepting = "lib/format.js,lib/names.js")

        val stored = plugins.findByKey("shipped")!!
        assertThat(stored.name).describedAs("what it calls itself, not the file").isEqualTo("Shipped Greeter")
        assertThat(stored.summary).isEqualTo("Greets, from a library.")
        assertThat(stored.author).isEqualTo("Somebody")
        assertThat(stored.version).isEqualTo("2.1.0")
        assertThat(stored.icon).contains("shipped-face")
        // And the manifest is not a library: it is not code and never runs.
        assertThat(libraries.findByPluginIdOrderByPositionAsc(requireNotNull(stored.id)).map { it.path })
            .containsExactly("lib/format.js", "lib/names.js")
    }

    /** A manifest that will not parse is a plugin without one, not a refusal. */
    /**
     * An icon file may open the way a file does.
     *
     * A real SVG carries a licence comment, or an XML declaration, before its
     * root element - and the check was `startsWith("<svg")`, so one that did
     * was not recognised as a drawing. On the screen that came out as a
     * paragraph of markup where a glyph should have been, because what is not
     * a drawing is drawn as text.
     */
    @Test
    fun `an icon that opens with a comment is still a drawing`() {
        val archive = zipped(
            "plugin.js" to pluginSource,
            "lib/format.js" to format,
            "lib/names.js" to names,
            "plugin.json" to """{"key":"shipped","icon":"icon.svg"}""",
            "icon.svg" to "<!-- The mark, used with permission. -->" +
                """<svg xmlns="http://www.w3.org/2000/svg" id="commented-face"/>""",
        )

        load(archive, accepting = "lib/format.js,lib/names.js")

        assertThat(plugins.findByKey("shipped")!!.icon).contains("commented-face")
    }

    /**
     * The white glyph, found where the convention puts it.
     *
     * A manifest names one icon and the folder holds two - `icon.svg` beside
     * `icon-white.svg`, which is the marketplace's own convention and how its
     * catalog answers with a pair for a manifest that names one. Followed here
     * so a plugin loaded by hand draws the way the same plugin drawn from the
     * catalog does: the Slack mark was the dark one on a dark screen, but only
     * when it had been loaded from a file.
     */
    @Test
    fun `a zip picks up the white glyph beside the icon, unasked`() {
        val archive = zipped(
            "plugin.js" to pluginSource,
            "lib/format.js" to format,
            "lib/names.js" to names,
            "plugin.json" to """{"key":"shipped","icon":"icon.svg"}""",
            "icon.svg" to """<svg xmlns="http://www.w3.org/2000/svg" id="for-light"/>""",
            "icon-white.svg" to """<svg xmlns="http://www.w3.org/2000/svg" id="for-dark"/>""",
        )

        load(archive, accepting = "lib/format.js,lib/names.js")

        val stored = plugins.findByKey("shipped")!!
        assertThat(stored.icon).contains("for-light")
        assertThat(stored.iconDark).describedAs("found without being named").contains("for-dark")
    }

    /** And a plugin with one icon has it on both grounds, which is its choice. */
    @Test
    fun `a zip with one icon stores one`() {
        val archive = zipped(
            "plugin.js" to pluginSource,
            "lib/format.js" to format,
            "lib/names.js" to names,
            "plugin.json" to """{"key":"shipped","icon":"icon.svg"}""",
            "icon.svg" to """<svg xmlns="http://www.w3.org/2000/svg" id="only-one"/>""",
        )

        load(archive, accepting = "lib/format.js,lib/names.js")

        val stored = plugins.findByKey("shipped")!!
        assertThat(stored.icon).contains("only-one")
        assertThat(stored.iconDark).isNull()
    }

    /**
     * And the zip path holds an icon to the same rule the others do. It held
     * it to none at all, so a manifest naming something that is not a picture
     * put that file's text on the screen.
     */
    @Test
    fun `a manifest naming something that is not a drawing gets no icon`() {
        val archive = zipped(
            "plugin.js" to pluginSource,
            "lib/format.js" to format,
            "lib/names.js" to names,
            "plugin.json" to """{"key":"shipped","icon":"notes.svg"}""",
            "notes.svg" to "# Notes. This is not a picture.",
        )

        load(archive, accepting = "lib/format.js,lib/names.js")

        assertThat(plugins.findByKey("shipped")!!.icon).isNull()
    }

    @Test
    fun `a manifest nobody can read leaves the plugin loadable`() {
        val archive = zipped(
            "plugin.js" to pluginSource,
            "lib/format.js" to format,
            "lib/names.js" to names,
            "plugin.json" to "{ this is not json",
        )

        load(archive, accepting = "lib/format.js,lib/names.js")

        val stored = plugins.findByKey("shipped")!!
        assertThat(stored.summary).isNull()
        // The filename, which is all there was — and exactly the poor name a
        // manifest exists to replace: the archive's main file is plugin.js.
        assertThat(stored.name).isEqualTo("plugin")
    }

    /**
     * A plugin folder is a folder somebody works in.
     *
     * The packer zips what is there, so a README, a licence and a lockfile
     * arrive beside the code - and refusing the archive over one of them meant
     * a plugin that builds could not be loaded. They are passed over; what the
     * refusal was guarding is code nobody declared, which is still caught.
     */
    @Test
    fun `a zip may carry a README, and it is simply not part of the plugin`() {
        val archive = zipped(
            "plugin.js" to pluginSource,
            "lib/format.js" to format,
            "lib/names.js" to names,
            "README.md" to "# Shipped. What this plugin does.",
            "LICENSE" to "MIT",
            "package-lock.json" to "{}",
        )

        load(archive, accepting = "lib/format.js,lib/names.js")

        val stored = plugins.findByKey("shipped")!!
        assertThat(libraries.findByPluginIdOrderByPositionAsc(requireNotNull(stored.id)).map { it.path })
            .describedAs("the code, and only the code")
            .containsExactly("lib/format.js", "lib/names.js")
        assertThat(pluginSources.sourceOf(stored)).doesNotContain("What this plugin does.")
    }

    /**
     * And the thing the refusal was for is still refused: a .js nobody
     * declared does not ride in on the same permission.
     */
    @Test
    fun `an undeclared script is still refused, README or no README`() {
        val archive = zipped(
            "plugin.js" to pluginSource,
            "lib/format.js" to format,
            "lib/names.js" to names,
            "README.md" to "# Shipped",
            "lib/extra.js" to "export const x = 1;",
        )

        val failure = runCatching { load(archive, accepting = "lib/format.js,lib/names.js,lib/extra.js") }
            .exceptionOrNull()

        assertThat(failure).isInstanceOf(PluginContractException::class.java)
        assertThat(failure!!.message).contains("lib/extra.js")
    }

    @Test
    fun `a set already allowed is not asked about again`() {
        val archive = zipped("plugin.js" to pluginSource, "lib/format.js" to format, "lib/names.js" to names)
        load(archive, accepting = "lib/format.js,lib/names.js")

        // The same bundle again, with no acceptance: nothing new is being
        // handed over, so nothing is asked.
        load(archive)

        assertThat(plugins.findByKey("shipped")).isNotNull
    }
}
