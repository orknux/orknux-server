package io.mszymanski.orknux.server.mcp

import io.mszymanski.orknux.server.action.FunctionParam
import io.mszymanski.orknux.server.action.ValueType
import io.mszymanski.orknux.server.action.WorkflowFunction
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.obj.ObjectProperty
import io.mszymanski.orknux.server.obj.PropertyKind
import io.mszymanski.orknux.server.obj.WorkflowObject
import io.mszymanski.orknux.server.obj.WorkflowObjectRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import tools.jackson.databind.ObjectMapper

/**
 * What a model can find out about a workspace's code.
 *
 * Asked of the tools directly rather than through a model: what a model does
 * with an answer is its business, and what is worth checking is that the answer
 * carries the source, that it is the TypeScript somebody wrote rather than the
 * JavaScript it compiled to, and that the workspace is a boundary — a scope on
 * one workspace must not read another's code, which is the whole of the
 * protection here.
 */
@SpringBootTest
class OrknuxFunctionToolsTest(
    @Autowired val tools: OrknuxTools,
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val objects: WorkflowObjectRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId: Long = 0
    private var elsewhereId: Long = 0

    @BeforeEach
    fun reset() {
        /*
         * The workspaces first: each takes its actions and functions with it.
         * Functions first was refused by the foreign key whenever a workspace an
         * earlier class left behind - QuickChatRoundsTest's "support" - had been
         * given the research modes by the seeder, whose actions call research
         * functions. That happens on any application start after it, so it
         * depended on the order the suite ran in.
         */
        audit.deleteAll()
        workspaces.deleteAll()
        functions.deleteAll()
        objects.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "support")).id)
        elsewhereId = requireNotNull(workspaces.save(Workspace(name = "billing")).id)
    }

    private fun scope() = OrknuxScope(workspaceId = workspaceId)

    private fun store(workspace: Long, name: String) = functions.save(
        WorkflowFunction(
            workspaceId = workspace,
            name = name,
            description = "Whether the sender is one of us.",
            source = "export default async function $name(email) { return email.endsWith('@acme.test'); }",
            typescript = "export default async function $name(email: string) { return email.endsWith('@acme.test'); }",
            returnType = ValueType.BOOLEAN,
            params = mutableListOf(FunctionParam(name = "email", type = ValueType.STRING)),
        ),
    )

    @Test
    fun `the list says what each function takes and gives back`() {
        store(workspaceId, "isTeammate")
        store(elsewhereId, "isBillable")

        val answer = tools.run(scope(), "orknux_functions", "{}")

        assertThat(answer).contains("isTeammate").contains("(email: string)").contains("BOOLEAN")
        // Another workspace's code is not this workspace's business.
        assertThat(answer).doesNotContain("isBillable")
    }

    @Test
    fun `one function comes back with the source somebody wrote`() {
        store(workspaceId, "isTeammate")

        val answer = tools.run(scope(), "orknux_function", """{"function":"isTeammate"}""")

        assertThat(answer).contains("typescript")
        // The annotated half: what the editor holds, and what a suggestion has
        // to be written against.
        assertThat(answer).contains("email: string")
    }

    /**
     * The whole point of writing a description on a field: it reaches the model.
     *
     * A signature says `customer: Customer` and stops, which tells a model that
     * something of that name exists and nothing about what is in it - so the
     * body it writes reaches for fields it invented. Sending the shape with the
     * function closes that, and sending each field's description closes the
     * half a model could never infer: `tier` is a word read three ways, and the
     * sentence beside it is read one way.
     */
    @Test
    fun `a parameter that names an object arrives with the object's fields and what they mean`() {
        val customer = objects.save(
            WorkflowObject(
                workspaceId = workspaceId,
                name = "Customer",
                description = "Who is asking, and what they are entitled to.",
                properties = mutableListOf(
                    ObjectProperty(
                        name = "tier",
                        kind = PropertyKind.STRING,
                        description = "The support plan they pay for: free, pro or enterprise.",
                    ),
                    ObjectProperty(name = "seats", kind = PropertyKind.NUMBER),
                ),
            ),
        )
        functions.save(
            WorkflowFunction(
                workspaceId = workspaceId,
                name = "isPriority",
                description = "Whether this customer jumps the queue.",
                source = "export default async function isPriority(customer) { return false; }",
                typescript = "export default async function isPriority(customer: Customer) { return false; }",
                returnType = ValueType.BOOLEAN,
                params = mutableListOf(
                    FunctionParam(name = "customer", type = ValueType.OBJECT, objectId = customer.id),
                ),
            ),
        )

        val answer = tools.run(scope(), "orknux_function", """{"function":"isPriority"}""")

        assertThat(answer).contains("Customer")
        assertThat(answer).contains("tier")
        assertThat(answer).contains("The support plan they pay for: free, pro or enterprise.")
        // A field nobody described is sent as a field nobody described, rather
        // than with its own name repeated back as if somebody had.
        assertThat(answer).contains("""{"name":"seats","type":"number","description":null}""")
    }

    /** A shape belongs to its workspace, the same way the code does. */
    @Test
    fun `an object in another workspace is not sent with the function`() {
        val theirs = objects.save(
            WorkflowObject(
                workspaceId = elsewhereId,
                name = "Invoice",
                properties = mutableListOf(
                    ObjectProperty(name = "secretRate", kind = PropertyKind.NUMBER, description = "What we charge."),
                ),
            ),
        )
        functions.save(
            WorkflowFunction(
                workspaceId = workspaceId,
                name = "borrowed",
                source = "export default async function borrowed(invoice) { return 0; }",
                typescript = "export default async function borrowed(invoice: Invoice) { return 0; }",
                returnType = ValueType.NUMBER,
                params = mutableListOf(
                    FunctionParam(name = "invoice", type = ValueType.OBJECT, objectId = theirs.id),
                ),
            ),
        )

        val answer = tools.run(scope(), "orknux_function", """{"function":"borrowed"}""")

        assertThat(answer).doesNotContain("secretRate")
        assertThat(answer).contains(""""returnedObject":null""")
    }

    @Test
    fun `suggesting a change is only offered where somebody can accept it`() {
        val watched = tools.specs(OrknuxScope(workspaceId = workspaceId, mayWrite = true, watched = true))
        val unwatched = tools.specs(OrknuxScope(workspaceId = workspaceId, mayWrite = true))

        assertThat(watched.map { it.name }).contains("orknux_suggest_function_code")
        // An agent inside a workflow has nobody to ask.
        assertThat(unwatched.map { it.name }).doesNotContain("orknux_suggest_function_code")
    }

    @Test
    fun `a suggestion names the function and carries the code, and saves nothing`() {
        val held = store(workspaceId, "isTeammate")
        val proposed = "export default async function isTeammate(email: string) { return false; }"

        val scope = OrknuxScope(workspaceId = workspaceId, mayWrite = true, watched = true)
        val arguments = mapper.writeValueAsString(
            mapOf("function" to "isTeammate", "code" to proposed, "note" to "Refuses everybody."),
        )

        val offered = tools.suggestionIn(scope, arguments)
        assertThat(offered?.functionId).isEqualTo(held.id)
        assertThat(offered?.code).isEqualTo(proposed)
        assertThat(offered?.note).isEqualTo("Refuses everybody.")

        val told = tools.run(scope, "orknux_suggest_function_code", arguments)
        assertThat(told).contains("shown").contains("isTeammate")

        // The point of the whole thing: the function is untouched until
        // somebody accepts it.
        assertThat(functions.findById(requireNotNull(held.id)).get().typescript).isEqualTo(held.typescript)
    }

    @Test
    fun `a suggestion for a function in another workspace is not a suggestion`() {
        store(elsewhereId, "isBillable")
        val scope = OrknuxScope(workspaceId = workspaceId, mayWrite = true, watched = true)
        val arguments = mapper.writeValueAsString(mapOf("function" to "isBillable", "code" to "export default 1;"))

        assertThat(tools.suggestionIn(scope, arguments)).isNull()
        assertThat(tools.run(scope, "orknux_suggest_function_code", arguments)).contains("Which function")
    }

    @Test
    fun `a function in another workspace is not there to be read`() {
        val other = store(elsewhereId, "isBillable")

        val byName = tools.run(scope(), "orknux_function", """{"function":"isBillable"}""")
        val byId = tools.run(scope(), "orknux_function", """{"function":"${other.id}"}""")

        assertThat(byName).contains("There is no function called isBillable here")
        assertThat(byId).contains("There is no function called ${other.id} here")
    }
}
