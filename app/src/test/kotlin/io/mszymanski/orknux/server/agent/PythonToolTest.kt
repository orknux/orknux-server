package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.server.action.CodeLanguage
import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.server.chat.AgentTools
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * A tool written in Python: saved with its language, checked against its
 * declared parameters the way a TypeScript tool is, refused what Python cannot
 * do, and run by an agent like any other tool.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class PythonToolTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val tools: AgentTools,
    @Autowired val agents: AgentRepository,
    @Autowired val toolRepository: AgentToolRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        agents.deleteAll()
        toolRepository.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
    }

    /*
     * Code goes in as a variable. A block string in the document itself is
     * dedented by GraphQL, which turns Python's indentation into a syntax error.
     */
    private fun create(name: String, source: String?, params: String = """[{ name: "text", type: STRING }]""", extra: String = "") =
        graphQlTester.document(
            """mutation(${'$'}source: String) { createTool(input: {
                 workspaceId: $workspaceId, name: "$name", language: PYTHON, params: $params, source: ${'$'}source $extra
               }) { id language source typescript } }""",
        ).variable("source", source).execute()

    @Test
    fun `a Python tool is saved with its language, the same text in both halves, and runs for an agent`() {
        val source = "def main(text):\n    return {'said': text.upper()}\n"
        val created = create("shout", source)
        created.path("createTool.language").entity(String::class.java).isEqualTo("PYTHON")
        created.path("createTool.source").entity(String::class.java).isEqualTo(source)
        created.path("createTool.typescript").entity(String::class.java).isEqualTo(source)

        val agent = agent("Reviewer", granted = "shout")
        val answer = tools.run(agent, ToolCall(id = "call_1", name = "shout", arguments = """{"text":"hello"}"""))

        assertThat(answer).isEqualTo("""{"said": "HELLO"}""")
    }

    @Test
    fun `a new Python tool with no code starts from a main that takes its parameters`() {
        create("blank", null).path("createTool.source").entity(String::class.java)
            .isEqualTo("def main(text):\n    # What this returns is handed back to the agent that called it.\n    return {\"ok\": True}\n")
    }

    @Test
    fun `code that does not parse, or takes other arguments than declared, is refused`() {
        create("broken", "def main(text:\n    return 1\n").errors().expect { it.extensions["code"] == "ToolSourceInvalid" }.verify()
        create("mismatch", "def main(a, b):\n    return 1\n").errors()
            .expect { it.message?.startsWith("The code takes 2 arguments, but this tool is handed 1") == true }.verify()
    }

    @Test
    fun `a Python tool cannot import JavaScript`() {
        val function = graphQlTester.document(
            """mutation { createFunction(input: { workspaceId: $workspaceId, name: "helper", returnType: STRING,
                 source: "export default function helper() { return 'x'; }",
                 typescript: "export default function helper(): string { return 'x'; }" }) { id } }""",
        ).execute().path("createFunction.id").entity(Long::class.java).get()

        create("importer", "def main(text):\n    return 1\n", extra = """, imports: [{ functionId: $function, name: "helper" }]""")
            .errors().expect {
                it.message == "importer is written in Python, and a Python tool cannot import the workspace's JavaScript functions or libraries" &&
                    it.extensions["code"] == "ToolPythonImports"
            }.verify()
    }

    @Test
    fun `the language moves only with code written in it`() {
        val id = create("shout", "def main(text):\n    return text\n").path("createTool.id").entity(Long::class.java).get()

        graphQlTester.document("""mutation { updateTool(id: $id, input: { language: TYPESCRIPT }) { id } }""")
            .execute().errors().expect {
                it.message == "shout can only become TypeScript with code written in TypeScript: a tool's language and its code are saved together" &&
                    it.extensions["code"] == "ToolLanguageNeedsCode"
            }.verify()

        val typescript = "export default function shout(text: string) { return text; }"
        val javascript = "export default function shout(text) { return text; }"
        graphQlTester.document(
            """mutation(${'$'}js: String, ${'$'}ts: String) { updateTool(id: $id, input: { language: TYPESCRIPT, source: ${'$'}js, typescript: ${'$'}ts }) { language } }""",
        ).variable("js", javascript).variable("ts", typescript)
            .execute().path("updateTool.language").entity(String::class.java).isEqualTo("TYPESCRIPT")
        assertThat(toolRepository.findByIdOrNull(id)?.language).isEqualTo(CodeLanguage.TYPESCRIPT)
    }

    @Test
    fun `validate parses Python when asked to`() {
        graphQlTester.document(
            """mutation(${'$'}s: String!) { validateToolSource(workspaceId: $workspaceId, source: ${'$'}s, language: PYTHON) { valid line } }""",
        ).variable("s", "def main(:\n    pass").execute()
            .path("validateToolSource.valid").entity(Boolean::class.java).isEqualTo(false)
            .path("validateToolSource.line").entity(Int::class.java).isEqualTo(1)
    }

    private fun agent(name: String, granted: String): Agent {
        val id = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "$name", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()
        graphQlTester.document("""mutation { updateAgent(id: $id, input: { name: "$name", tools: ["$granted"] }) { tools } }""").execute()
        return requireNotNull(agents.findByIdOrNull(id))
    }
}
