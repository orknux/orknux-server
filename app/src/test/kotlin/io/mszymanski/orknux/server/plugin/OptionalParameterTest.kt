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

/**
 * A parameter a call may leave out, and what arrives instead.
 *
 * What this replaces, in the words the shipped plugins used: "0 for the
 * default", "an empty string to use the configured one". Thirty-six places
 * said some variant of it, every one a workaround for the same thing - all
 * positional arguments had to be supplied, so a plugin invented a sentinel and
 * then spent a sentence explaining it. Those sentences live in tool
 * descriptions a model reads on every call.
 *
 * The claims: a default reaches the row, a call that omits the argument gets
 * it, one that supplies it gets what it supplied, a default that is not of the
 * parameter's type is refused at load rather than at the call, and an optional
 * parameter before a required one is refused because the arguments are
 * positional.
 */
@SpringBootTest
@WithMockUser(username = "alice", roles = ["ADMINS"])
class OptionalParameterTest(
    @Autowired val upload: PluginUploadAPI,
    @Autowired val plugins: PluginRepository,
    @Autowired val declarations: PluginDeclarations,
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val functionApi: FunctionAPI,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        plugins.deleteAll()
        // Not the release's own, which every workspace's Research action calls (#659) and start-up writes back anyway.
        functions.deleteAll(functions.findAll().filter { it.scope != io.mszymanski.orknux.server.action.FunctionScope.EMBEDDED })
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
    }

    /** `search(query, limit = 20)` — the shape the sentinel was standing in for. */
    private val source = """
        export default class Searcher extends OrknuxPlugin {
          id() { return 'searcher'; }
          apiVersion() { return 1; }
          functions() {
            return [new OrknuxFunction({
              name: 'search',
              params: [
                { name: 'query', type: 'string' },
                { name: 'limit', type: 'number', default: 20 },
              ],
              returnType: 'map',
              run: (query, limit) => ({ query: query, limit: limit }),
            })];
          }
        }
    """.trimIndent()

    private fun load(text: String = source) =
        upload.upload(MockMultipartFile("file", "searcher.js", "text/javascript", text.toByteArray()), null, null)

    private fun call(vararg given: Pair<String, String>): String {
        val function = functions.findAll().single { it.name == "searcher_search" }
        val run = functionApi.runFunction(
            RunFunctionInput(
                workspaceId = workspaceId,
                functionId = requireNotNull(function.id),
                arguments = given.map { FunctionArgumentInput(name = it.first, json = it.second) },
            ),
        )
        assertThat(run.error).describedAs("the call failed").isNull()
        return requireNotNull(run.returned)
    }

    @Test
    fun `a default reaches the row, and the signature says which is optional`() {
        load()

        val param = functions.findAll().single { it.name == "searcher_search" }.params.single { it.name == "limit" }
        assertThat(param.required).isFalse()
        assertThat(param.defaultJson).isEqualTo("20")

        // `?` the way TypeScript marks it, because this reads beside TypeScript
        // everywhere it is shown.
        val declared = declarations.read(plugins.findByKey("searcher")!!.declaredFunctions).single()
        assertThat(declared.signature).isEqualTo("(query: string, limit?: number): map")
    }

    @Test
    fun `a call that leaves it out gets the default`() {
        load()

        assertThat(call("query" to "\"hello\"")).contains("\"limit\":20")
    }

    @Test
    fun `and one that supplies it gets what it supplied`() {
        load()

        assertThat(call("query" to "\"hello\"", "limit" to "5")).contains("\"limit\":5")
    }

    /**
     * Refused where it is written rather than where it is used. A default of
     * the wrong type only shows up on the call that omits the argument, which
     * may be months later and in somebody else's workflow.
     */
    @Test
    fun `a default that is not the parameter's type is refused at load`() {
        val wrong = source.replace("default: 20", "default: 'twenty'")

        val failure = runCatching { load(wrong) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(PluginDeclarationInvalidException::class.java)
        assertThat(failure!!.message).contains("limit").contains("number")
        assertThat(plugins.findByKey("searcher")).isNull()
    }

    /**
     * The arguments are positional, so "left out" only means anything at the
     * end. A required parameter after an optional one is a signature nothing
     * downstream could read.
     */
    @Test
    fun `an optional parameter before a required one is refused`() {
        // Written out rather than reordered by replacing text: the last
        // attempt at that matched nothing, so the source was unchanged and the
        // test passed a plugin it meant to refuse.
        val wrong = """
            export default class Searcher extends OrknuxPlugin {
              id() { return 'searcher'; }
              apiVersion() { return 1; }
              functions() {
                return [new OrknuxFunction({
                  name: 'search',
                  params: [
                    { name: 'limit', type: 'number', default: 20 },
                    { name: 'query', type: 'string' },
                  ],
                  returnType: 'map',
                  run: (limit, query) => ({ query: query, limit: limit }),
                })];
              }
            }
        """.trimIndent()

        val failure = runCatching { load(wrong) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(PluginDeclarationInvalidException::class.java)
        assertThat(failure!!.message).contains("positional")
    }

    /**
     * A default and `required: true` together is a default that can never
     * apply - which is a mistake worth saying out loud rather than a value to
     * ignore.
     */
    @Test
    fun `a default on a required parameter is refused where it was written`() {
        val wrong = source.replace("default: 20", "default: 20, required: true")

        val failure = runCatching { load(wrong) }.exceptionOrNull()

        assertThat(failure!!.message).contains("can never apply")
    }

    /** And a plugin that says nothing still has parameters everything must pass. */
    @Test
    fun `saying nothing still means required`() {
        load(source.replace(", default: 20", ""))

        val param = functions.findAll().single { it.name == "searcher_search" }.params.single { it.name == "limit" }
        assertThat(param.required).isTrue()
        assertThat(param.defaultJson).isNull()
    }
}
