package io.mszymanski.orknux.server.variable

import io.mszymanski.orknux.server.action.FunctionExternal
import io.mszymanski.orknux.server.plugin.PluginRepository
import io.mszymanski.orknux.server.plugin.PluginUploadAPI
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.context.support.WithMockUser

/**
 * A variable that is a list, or a type a plugin defines. Issue #377.
 *
 * A Slack user id is a string only some values of are real, and until now a
 * variable holding one was a text box: a typo was found by the function that
 * failed at three in the morning. A plugin may now name a type over a base
 * type, complete values of it as they are typed, and refuse one when it is
 * saved - with its own reason, which is the one worth reading.
 *
 * The plugin here is a palette rather than Slack, because what is measured is
 * the server's half: that the type is offered, that the picker is answered,
 * that a refused value is not saved, and that a list reaches a function as an
 * array. A type that needs no network says all of that without one.
 *
 * Makes a workspace, a catalog and a plugin, and removes them.
 */
@SpringBootTest
@WithMockUser(username = "alice", roles = ["ADMINS"])
class VariableTypesTest(
    @Autowired val api: VariableAPI,
    @Autowired val arguments: VariableArguments,
    @Autowired val upload: PluginUploadAPI,
    @Autowired val plugins: PluginRepository,
    @Autowired val variables: WorkspaceVariableRepository,
    @Autowired val catalogs: VariableCatalogRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0
    private var catalogId: Long = 0

    @BeforeEach
    fun reset() {
        plugins.deleteAll()
        variables.deleteAll()
        catalogs.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        catalogId = requireNotNull(catalogs.save(VariableCatalog(workspaceId = workspaceId, name = "Defaults")).id)
    }

    private val palette = """
        export default class Palette extends OrknuxPlugin {
          id() { return 'palette'; }
          apiVersion() { return 1; }
          types() {
            return [
              new OrknuxType({
                name: 'Colour',
                description: 'One of the named colours.',
                base: 'string',
                parameters: [{ name: 'shade', type: 'string', required: false }],
                suggest: (typed, args) =>
                  ['red', 'green', 'blue']
                    .filter((one) => one.startsWith(typed))
                    .map((one) => ({ value: one, label: (args.shade ? args.shade + ' ' : '') + one })),
                validate: (value) =>
                  ['red', 'green', 'blue'].includes(value) ? { ok: true } : { ok: false, reason: value + ' is not a colour' },
              }),
            ];
          }
        }
    """.trimIndent()

    private fun load() =
        upload.upload(MockMultipartFile("file", "palette.js", "text/javascript", palette.toByteArray()), null, null)

    private fun make(
        name: String,
        type: VariableType,
        value: String?,
        elementType: VariableType? = null,
        customType: String? = null,
        typeArguments: String? = null,
    ) = api.createVariable(
        CreateVariableInput(
            workspaceId = workspaceId,
            catalogId = catalogId,
            name = name,
            type = type,
            kind = VariableKind.VALUE,
            elementType = elementType,
            customType = customType,
            typeArguments = typeArguments,
            value = value,
        ),
    )

    /* -------------------------------------------------- a plugin's type ---- */

    @Test
    fun `a loaded plugin's type is offered, under the plugin's key`() {
        load()

        val offered = api.variableTypes(workspaceId)

        val colour = offered.single { it.key == "palette:Colour" }
        assertThat(colour.base).isEqualTo(VariableType.STRING)
        assertThat(colour.description).isEqualTo("One of the named colours.")
        assertThat(colour.parameters.map { it.name }).containsExactly("shade")
        assertThat(colour.suggests).isTrue()
        assertThat(colour.validates).isTrue()
    }

    @Test
    fun `the picker is answered by the plugin, told what the variable was told`() {
        load()

        val offered = api.variableSuggestions(workspaceId, "palette:Colour", """{"shade":"dark"}""", "gr")

        assertThat(offered).hasSize(1)
        assertThat(offered.single().value).isEqualTo("green")
        assertThat(offered.single().label).isEqualTo("dark green")
    }

    /**
     * The whole point: the plugin says no, with its own reason, and nothing
     * is saved.
     */
    @Test
    fun `a value the plugin refuses is not saved, and its reason is the one given`() {
        load()

        assertThatThrownBy { make("favourite", VariableType.STRING, "mauve", customType = "palette:Colour") }
            .isInstanceOf(VariableValueInvalidException::class.java)
            .hasMessageContaining("mauve is not a colour")
        assertThat(variables.findByWorkspaceId(workspaceId)).isEmpty()
    }

    @Test
    fun `and one it accepts is`() {
        load()

        val made = make("favourite", VariableType.STRING, "red", customType = "palette:Colour")

        assertThat(made.customType).isEqualTo("palette:Colour")
        assertThat(made.value).isEqualTo("red")
    }

    @Test
    fun `a type no enabled plugin defines is refused by name`() {
        assertThatThrownBy { make("favourite", VariableType.STRING, "red", customType = "palette:Colour") }
            .isInstanceOf(VariableTypeUnknownException::class.java)
    }

    /** A type is a name over one base; a variable of another base cannot wear it. */
    @Test
    fun `a type over a string cannot be put on a number`() {
        load()

        assertThatThrownBy { make("favourite", VariableType.NUMBER, "3", customType = "palette:Colour") }
            .isInstanceOf(VariableValueInvalidException::class.java)
            .hasMessageContaining("string underneath")
    }

    /* ----------------------------------------------------------- a list ---- */

    @Test
    fun `a list of numbers reaches a function as an array of numbers`() {
        val made = make("thresholds", VariableType.LIST, """["1", "2.5", "3"]""", elementType = VariableType.NUMBER)

        val handed = arguments.of(listOf(FunctionExternal(variableId = made.id)), "test")

        assertThat(handed).containsExactly("[1,2.5,3]")
    }

    @Test
    fun `and a list of strings as an array of strings`() {
        val made = make("channels", VariableType.LIST, """["#ops", "#dev"]""", elementType = VariableType.STRING)

        assertThat(arguments.of(listOf(FunctionExternal(variableId = made.id)), "test"))
            .containsExactly("""["#ops","#dev"]""")
    }

    @Test
    fun `an element that is not of the element type is refused at the save`() {
        assertThatThrownBy { make("thresholds", VariableType.LIST, """["1", "x"]""", elementType = VariableType.NUMBER) }
            .isInstanceOf(VariableValueInvalidException::class.java)
            .hasMessageContaining("\"x\" is not a number")
    }

    @Test
    fun `a list that is not a JSON array is refused at the save`() {
        assertThatThrownBy { make("channels", VariableType.LIST, "#ops, #dev", elementType = VariableType.STRING) }
            .isInstanceOf(VariableValueInvalidException::class.java)
            .hasMessageContaining("JSON array")
    }

    /** Each element of a list of a plugin's type is put to the plugin. */
    @Test
    fun `a list of a plugin's type has every element checked`() {
        load()

        assertThatThrownBy {
            make("palette", VariableType.LIST, """["red", "mauve"]""", elementType = VariableType.STRING, customType = "palette:Colour")
        }
            .isInstanceOf(VariableValueInvalidException::class.java)
            .hasMessageContaining("mauve is not a colour")

        val made = make("palette", VariableType.LIST, """["red", "blue"]""", elementType = VariableType.STRING, customType = "palette:Colour")
        assertThat(made.elementType).isEqualTo(VariableType.STRING)
    }

    /* -------------------------------------------------------- the scalars -- */

    @Test
    fun `a number that is not one is refused at the save rather than found by a function`() {
        assertThatThrownBy { make("limit", VariableType.NUMBER, "ten") }
            .isInstanceOf(VariableValueInvalidException::class.java)
            .hasMessageContaining("not a number")
    }

    @Test
    fun `and a boolean that is neither`() {
        assertThatThrownBy { make("enabled", VariableType.BOOLEAN, "yes") }
            .isInstanceOf(VariableValueInvalidException::class.java)
            .hasMessageContaining("neither true nor false")
    }
}
