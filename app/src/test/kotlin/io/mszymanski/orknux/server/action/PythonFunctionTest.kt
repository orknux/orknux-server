package io.mszymanski.orknux.server.action

import io.mszymanski.orknux.server.agent.AgentToolRepository
import io.mszymanski.orknux.server.condition.WorkflowConditionRepository
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
 * A workspace function written in Python: saved with its language, checked
 * against its parameters, run the way a workflow runs it, and kept out of the
 * imports that only JavaScript can take part in - in both directions.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class PythonFunctionTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val actions: WorkflowActionRepository,
    @Autowired val conditions: WorkflowConditionRepository,
    @Autowired val tools: AgentToolRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        actions.deleteAll()
        conditions.deleteAll()
        tools.deleteAll()
        functions.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
    }

    /* Code goes in as a variable: a block string in the document is dedented, which breaks Python. */
    private fun create(name: String, source: String?, language: String = "PYTHON", extra: String = "") =
        graphQlTester.document(
            """mutation(${'$'}source: String, ${'$'}typescript: String) { createFunction(input: {
                 workspaceId: $workspaceId, name: "$name", language: $language, returnType: MAP,
                 params: [{ name: "a", type: NUMBER }, { name: "b", type: NUMBER }],
                 source: ${'$'}source, typescript: ${'$'}typescript $extra
               }) { id language source typescript signature } }""",
        ).variable("source", source).variable("typescript", if (language == "PYTHON") null else source).execute()

    private fun run(id: Long, a: String, b: String) = graphQlTester.document(
        """mutation { runFunction(input: { workspaceId: $workspaceId, functionId: $id,
             arguments: [{ name: "a", json: "$a" }, { name: "b", json: "$b" }] }) { ok returned error } }""",
    ).execute()

    @Test
    fun `a Python function is saved with its language and runs as a workflow runs it`() {
        val source = "import math\n\ndef main(a, b):\n    return {'sum': a + b, 'root': math.sqrt(a * b)}\n"
        val created = create("addUp", source)
        created.path("createFunction.language").entity(String::class.java).isEqualTo("PYTHON")
        created.path("createFunction.typescript").entity(String::class.java).isEqualTo(source)
        val id = created.path("createFunction.id").entity(Long::class.java).get()

        run(id, "4", "9")
            .path("runFunction.ok").entity(Boolean::class.java).isEqualTo(true)
            .path("runFunction.returned").entity(String::class.java).isEqualTo("""{"sum": 13, "root": 6.0}""")
    }

    @Test
    fun `a new Python function starts from a main that takes its parameters and hands them on`() {
        val source = create("blank", null).path("createFunction.source").entity(String::class.java).get()

        assertThat(source).startsWith("def main(a, b):\n")
        assertThat(source).contains("orknux.http.get(url, headers)").endsWith("    return {'a': a, 'b': b}\n")
        assertThat(source).doesNotContainIgnoringCase("typescript").doesNotContain("export")
    }

    @Test
    fun `code that takes other arguments than declared is refused`() {
        create("mismatch", "def main(a):\n    return a\n").errors()
            .expect { it.message?.startsWith("The code takes 1 argument") == true }.verify()
    }

    @Test
    fun `a Python function cannot be imported, by a function or a tool`() {
        val python = create("helper", "def main(a, b):\n    return a\n").path("createFunction.id").entity(Long::class.java).get()
        val javascript = "export default function user(a, b) { return imports.helper(a, b); }"

        create("user", javascript, language = "TYPESCRIPT", extra = """, imports: [{ functionId: $python, name: "helper" }]""")
            .errors().expect { it.extensions["code"] == "ImportPython" }.verify()
        graphQlTester.document(
            """mutation { createTool(input: { workspaceId: $workspaceId, name: "caller",
                 source: "export default function caller(input) { return 1; }",
                 typescript: "export default function caller(input: Record<string, unknown>) { return 1; }",
                 imports: [{ functionId: $python, name: "helper" }] }) { id } }""",
        ).execute().errors().expect { it.extensions["code"] == "ImportPython" }.verify()
    }

    @Test
    fun `a function something imports stays TypeScript`() {
        val shared = create(
            "shared",
            "export default function shared(a, b) { return a + b; }",
            language = "TYPESCRIPT",
        ).path("createFunction.id").entity(Long::class.java).get()
        create(
            "user",
            "export default function user(a, b) { return imports.shared(a, b); }",
            language = "TYPESCRIPT",
            extra = """, imports: [{ functionId: $shared, name: "shared" }]""",
        ).path("createFunction.id").entity(Long::class.java).get()

        graphQlTester.document(
            """mutation(${'$'}s: String) { updateFunction(id: $shared, input: { language: PYTHON, source: ${'$'}s }) { id } }""",
        ).variable("s", "def main(a, b):\n    return a + b\n").execute().errors().expect {
            it.message == "shared is imported by user, so it has to stay TypeScript: a Python function cannot be imported" &&
                it.extensions["code"] == "FunctionImportedStaysTypeScript"
        }.verify()
        assertThat(functions.findByIdOrNull(shared)?.language).isEqualTo(CodeLanguage.TYPESCRIPT)
    }

    @Test
    fun `the language moves only with code written in it, and a Python function imports nothing`() {
        val id = create("moving", "export default function moving(a, b) { return a; }", language = "TYPESCRIPT")
            .path("createFunction.id").entity(Long::class.java).get()

        graphQlTester.document("""mutation { updateFunction(id: $id, input: { language: PYTHON }) { id } }""")
            .execute().errors().expect { it.extensions["code"] == "FunctionLanguageNeedsCode" }.verify()

        graphQlTester.document(
            """mutation(${'$'}s: String) { updateFunction(id: $id, input: { language: PYTHON, source: ${'$'}s }) { language source } }""",
        ).variable("s", "def main(a, b):\n    return a * b\n").execute()
            .path("updateFunction.language").entity(String::class.java).isEqualTo("PYTHON")
        run(id, "6", "7").path("runFunction.returned").entity(String::class.java).isEqualTo("42")

        val other = create("other", "export default function other(a, b) { return a; }", language = "TYPESCRIPT")
            .path("createFunction.id").entity(Long::class.java).get()
        graphQlTester.document("""mutation { updateFunction(id: $id, input: { imports: [{ functionId: $other, name: "other" }] }) { id } }""")
            .execute().errors().expect { it.extensions["code"] == "FunctionPythonImports" }.verify()
    }

    @Test
    fun `validate parses Python when asked to`() {
        graphQlTester.document(
            """mutation(${'$'}s: String!) { validateFunctionSource(workspaceId: $workspaceId, source: ${'$'}s, language: PYTHON) { valid line } }""",
        ).variable("s", "def main(:\n    pass").execute()
            .path("validateFunctionSource.valid").entity(Boolean::class.java).isEqualTo(false)
            .path("validateFunctionSource.line").entity(Int::class.java).isEqualTo(1)
    }
}
