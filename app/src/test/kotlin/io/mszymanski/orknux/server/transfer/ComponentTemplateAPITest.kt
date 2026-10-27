package io.mszymanski.orknux.server.transfer

import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.agent.AgentSkillRepository
import io.mszymanski.orknux.server.agent.AgentToolRepository
import io.mszymanski.orknux.server.agent.SkillCatalogRepository
import io.mszymanski.orknux.server.condition.WorkflowConditionRepository
import io.mszymanski.orknux.server.obj.WorkflowObjectRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.ObjectMapper

/**
 * Templates: an export the installation keeps, and the button that uses it.
 *
 * What is worth holding here is that a template is *not a second feature*. The
 * round trip below — save a function in one workspace, use it in another — has
 * to arrive with the same code, the same renaming on collision and the same
 * refusal for a version this installation does not read as an uploaded file
 * would, because it goes through the same exporter and the same importer. A
 * test that only proved the row was written would pass on a second
 * implementation that happened to agree today.
 *
 * The other half is who: publishing is installation-wide and administrators
 * only, using is anybody who may write to the workspace.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ComponentTemplateAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val objects: WorkflowObjectRepository,
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val conditions: WorkflowConditionRepository,
    @Autowired val tools: AgentToolRepository,
    @Autowired val skills: AgentSkillRepository,
    @Autowired val catalogs: SkillCatalogRepository,
    @Autowired val templates: ComponentTemplateRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val mapper: ObjectMapper,
    @Autowired val workflows: io.mszymanski.orknux.server.workflow.WorkflowRepository,
    @Autowired val actions: io.mszymanski.orknux.server.action.WorkflowActionRepository,
) {

    private var from: Long = 0
    private var into: Long = 0

    @BeforeEach
    fun reset() {
        templates.deleteAll()
        // What points at a function first, or SQLite refuses the delete wherever an earlier class left a workflow.
        workflows.deleteAll()
        actions.deleteAll()
        conditions.deleteAll()
        functions.deleteAll()
        objects.deleteAll()
        tools.deleteAll()
        skills.deleteAll()
        catalogs.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        from = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        into = requireNotNull(workspaces.save(Workspace(name = "frontend")).id)
    }

    @Test
    fun `a template is a stored envelope, and using it is the import that already existed`() {
        val objectId = createObject(from, "Order")
        val functionId = createFunction(from, "normalise", returnObjectId = objectId)

        val templateId = saveAsTemplate(from, "FUNCTION", functionId, "Order normalising", "How we clean an order up")

        // The row holds the export and nothing invented beside it: what the page
        // shows about it is read back out of the file.
        val stored = mapper.readTree(templates.findById(templateId).get().envelope)
        assertThat(stored.path("formatVersion").asInt()).isEqualTo(COMPONENT_FORMAT_VERSION)
        assertThat(stored.path("components").values().map { it.path("kind").stringValue() })
            .containsExactly("OBJECT", "FUNCTION")

        val listed = graphQlTester.document(
            """query { componentTemplates { id name description kinds componentCount usable formatVersion
                 contents { kind name } } }""",
        ).execute()
        listed.path("componentTemplates[0].name").entity(String::class.java).isEqualTo("Order normalising")
        listed.path("componentTemplates[0].kinds").entityList(String::class.java)
            .containsExactly("OBJECT", "FUNCTION")
        listed.path("componentTemplates[0].componentCount").entity(Int::class.java).isEqualTo(2)
        listed.path("componentTemplates[0].usable").entity(Boolean::class.java).isEqualTo(true)

        // The plan is the import's plan, from the import's own reader.
        graphQlTester.document(
            """query { componentTemplatePlan(workspaceId: $into, templateId: $templateId) {
                 importable entries { kind name disposition } } }""",
        ).execute().path("componentTemplatePlan.importable").entity(Boolean::class.java).isEqualTo(true)

        use(into, templateId)

        val arrived = functions.findByWorkspaceIdAndName(into, "normalise")!!
        assertThat(arrived.source).isEqualTo(functions.findById(functionId).get().source)
        assertThat(arrived.returnObjectId).isEqualTo(objects.findByWorkspaceIdAndName(into, "Order")!!.id)
        // The original is untouched: a template is a copy, taken and then let go of.
        assertThat(functions.findById(functionId).get().workspaceId).isEqualTo(from)
    }

    @Test
    fun `a template does not follow what it was made from`() {
        val functionId = createFunction(from, "normalise")
        val templateId = saveAsTemplate(from, "FUNCTION", functionId, "Normalising", null)

        // Rewrite the function the template was taken from.
        graphQlTester.document(
            """mutation { updateFunction(id: $functionId, input: {
                 name: "normalise",
                 source: ${quote(CHANGED)}, typescript: ${quote(CHANGED)}, returnType: MAP }) { id } }""",
        ).execute().path("updateFunction.id").hasValue()
        assertThat(functions.findById(functionId).get().source).isEqualTo(CHANGED)

        use(into, templateId)

        // What arrived is the snapshot, not the rewrite.
        assertThat(functions.findByWorkspaceIdAndName(into, "normalise")!!.source).isNotEqualTo(CHANGED)
    }

    @Test
    fun `using a template into a workspace that has the name renames, and does not replace`() {
        val functionId = createFunction(from, "normalise")
        val theirs = createFunction(into, "normalise")
        val templateId = saveAsTemplate(from, "FUNCTION", functionId, "Normalising", null)

        use(into, templateId)

        assertThat(functions.findById(theirs).get().name).isEqualTo("normalise")
        assertThat(functions.findByWorkspaceIdAndName(into, "normalise_2")).isNotNull()
    }

    @Test
    fun `a template from a version this installation does not read is listed, and says why`() {
        val toolId = createTool(from, "lookup")
        val json = export(from, "TOOL", toolId)

        // Published while it could be read, and then the file is from the
        // future - which is what a rollback past a format version looks like
        // from here. Written straight to the row, because the API refuses it.
        val templateId = createTemplate("Lookup", null, json)
        val held = templates.findById(templateId).get()
        held.envelope = json.replace("\"formatVersion\" : 1", "\"formatVersion\" : 2")
        templates.save(held)

        val listed = graphQlTester.document(
            """query { componentTemplates { name usable problem kinds componentCount formatVersion } }""",
        ).execute()
        // Still on the list. A page that failed to load because one of forty
        // templates is unreadable is worse than one that says which.
        listed.path("componentTemplates[0].name").entity(String::class.java).isEqualTo("Lookup")
        listed.path("componentTemplates[0].usable").entity(Boolean::class.java).isEqualTo(false)
        listed.path("componentTemplates[0].problem").entity(String::class.java).satisfies { said ->
            assertThat(said).contains("format version 2").contains("reads version 1")
        }

        // And using it refuses in the same words rather than half-importing.
        graphQlTester.document(
            """mutation { useComponentTemplate(workspaceId: $into, templateId: $templateId) { importable } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors.single().message).contains("format version 2")
        }
        assertThat(tools.findByWorkspaceIdAndName(into, "lookup")).isNull()
    }

    @Test
    fun `a file that is not an export is refused at the form, not at the button`() {
        graphQlTester.document(
            """mutation { createComponentTemplate(input: {
                 name: "Nonsense", envelope: "{\"hello\":true}" }) { id } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors.single().message).contains("carries no formatVersion")
        }
        assertThat(templates.count()).isZero()
    }

    @Test
    fun `two templates cannot share a name, and the second is told rather than renamed`() {
        val toolId = createTool(from, "lookup")
        val json = export(from, "TOOL", toolId)
        createTemplate("Lookup", null, json)

        graphQlTester.document(
            """mutation { createComponentTemplate(input: { name: "lookup", envelope: ${quote(json)} }) { id } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors.single().message).contains("already has a template called lookup")
        }
        assertThat(templates.count()).isEqualTo(1)
    }

    /**
     * Publishing again under a template's name brings it up to date, once the
     * publisher says so: refused with a code the interface asks on, then
     * replacing the file and keeping the row.
     */
    @Test
    fun `publishing under a taken name replaces the template when told to, and only then`() {
        val toolId = createTool(from, "lookup")
        val templateId = createTemplate("Lookup", "First words", export(from, "TOOL", toolId))
        val skillId = createSkill(from, "Triage", "Support")
        val newer = export(from, "SKILL", skillId)

        graphQlTester.document(
            """mutation { createComponentTemplate(input: { name: "lookup", envelope: ${quote(newer)} }) { id } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors.single().extensions["code"]).isEqualTo("TemplateNameTaken")
        }
        assertThat(templates.findById(templateId).get().description).isEqualTo("First words")

        graphQlTester.document(
            """mutation { createComponentTemplate(input: { name: "lookup", description: "Newer", envelope: ${quote(newer)}, replace: true }) {
                 id kinds description } }""",
        ).execute().path("createComponentTemplate.id").entity(String::class.java).isEqualTo(templateId.toString())
            .path("createComponentTemplate.kinds").entityList(String::class.java).containsExactly("SKILL")
        assertThat(templates.count()).isEqualTo(1)
        assertThat(templates.findById(templateId).get().description).isEqualTo("Newer")
        assertThat(audit.findAll().map { it.message }).anyMatch { it.startsWith("Template Lookup replaced") }
    }

    @Test
    fun `rewording a template leaves the file alone, and replacing it is what changes it`() {
        val toolId = createTool(from, "lookup")
        val templateId = createTemplate("Lookup", "First words", export(from, "TOOL", toolId))
        val before = templates.findById(templateId).get().envelope

        graphQlTester.document(
            """mutation { updateComponentTemplate(id: $templateId, input: {
                 name: "Lookup", description: "Better words" }) { description componentCount } }""",
        ).execute().path("updateComponentTemplate.description").entity(String::class.java).isEqualTo("Better words")
        assertThat(templates.findById(templateId).get().envelope).isEqualTo(before)

        // Replacing is deliberate, and is the only way a template moves on.
        val skillId = createSkill(from, "Triage", "Support")
        graphQlTester.document(
            """mutation { updateComponentTemplate(id: $templateId, input: {
                 name: "Lookup", envelope: ${quote(export(from, "SKILL", skillId))} }) { kinds } }""",
        ).execute().path("updateComponentTemplate.kinds").entityList(String::class.java).containsExactly("SKILL")
    }

    @Test
    fun `the list narrows to the templates that hold a kind`() {
        createTemplate("Tooling", null, export(from, "TOOL", createTool(from, "lookup")))
        createTemplate("Knowing", null, export(from, "SKILL", createSkill(from, "Triage", "Support")))

        graphQlTester.document("""query { componentTemplates(holding: TOOL) { name } }""")
            .execute().path("componentTemplates[*].name").entityList(String::class.java).containsExactly("Tooling")
        graphQlTester.document("""query { componentTemplates(holding: FUNCTION) { name } }""")
            .execute().path("componentTemplates").entityList(Any::class.java).hasSize(0)
    }

    /** Offered where it was made from: an agent's template carries its tool, and is still not a tool template. */
    @Test
    fun `the list offers a template where it was made from, not wherever its contents would fit`() {
        createTool(from, "lookup")
        val agentId = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $from, name: "Researcher", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()
        graphQlTester.document(
            """mutation { updateAgent(id: $agentId, input: { name: "Researcher", tools: ["lookup"] }) { tools } }""",
        ).execute().errors().verify()
        createTemplate("Researching", null, export(from, "AGENT", agentId))

        graphQlTester.document("""query { componentTemplates { name kinds rootKinds } }""").execute()
            .path("componentTemplates[0].kinds").entityList(String::class.java).contains("AGENT", "TOOL")
            .path("componentTemplates[0].rootKinds").entityList(String::class.java).containsExactly("AGENT")
        graphQlTester.document("""query { componentTemplates(holding: AGENT) { name } }""")
            .execute().path("componentTemplates[*].name").entityList(String::class.java).containsExactly("Researching")
        graphQlTester.document("""query { componentTemplates(holding: TOOL) { name } }""")
            .execute().path("componentTemplates").entityList(Any::class.java).hasSize(0)
    }

    @Test
    @WithMockUser(username = "bob", roles = ["USERS"])
    fun `publishing is an administrator's, and it is refused rather than half done`() {
        graphQlTester.document(
            """mutation { createComponentTemplate(input: { name: "Mine", envelope: "{}" }) { id } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors.single().message).contains("administrator")
        }
        assertThat(templates.count()).isZero()
    }

    @Test
    fun `deleting a template leaves behind what it already created`() {
        val functionId = createFunction(from, "normalise")
        val templateId = saveAsTemplate(from, "FUNCTION", functionId, "Normalising", null)
        use(into, templateId)

        graphQlTester.document("""mutation { deleteComponentTemplate(id: $templateId) }""")
            .execute().path("deleteComponentTemplate").entity(Boolean::class.java).isEqualTo(true)

        assertThat(templates.count()).isZero()
        assertThat(functions.findByWorkspaceIdAndName(into, "normalise")).isNotNull()
        assertThat(audit.findAll().map { it.message }).contains("Template Normalising removed")
    }

    // ----------------------------------------------------------------- helpers

    private fun use(workspaceId: Long, templateId: Long) {
        graphQlTester.document(
            """mutation { useComponentTemplate(workspaceId: $workspaceId, templateId: $templateId) {
                 importable entries { name targetName disposition } } }""",
        ).execute().path("useComponentTemplate.importable").entity(Boolean::class.java).isEqualTo(true)
    }

    private fun createTemplate(name: String, description: String?, envelope: String): Long = graphQlTester.document(
        """mutation { createComponentTemplate(input: {
             name: "$name"${description?.let { ", description: ${quote(it)}" } ?: ""},
             envelope: ${quote(envelope)} }) { id } }""",
    ).execute().path("createComponentTemplate.id").entity(Long::class.java).get()

    private fun saveAsTemplate(
        workspaceId: Long,
        kind: String,
        id: Long,
        name: String,
        description: String?,
    ): Long = graphQlTester.document(
        """mutation { saveComponentAsTemplate(workspaceId: $workspaceId, kind: $kind, id: $id, depth: DEEP,
             input: { name: "$name"${description?.let { ", description: ${quote(it)}" } ?: ""} }) { id } }""",
    ).execute().path("saveComponentAsTemplate.id").entity(Long::class.java).get()

    private fun export(workspaceId: Long, kind: String, id: Long): String = graphQlTester.document(
        """query { exportComponent(workspaceId: $workspaceId, kind: $kind, id: $id, depth: DEEP) { json } }""",
    ).execute().path("exportComponent.json").entity(String::class.java).get()

    private fun quote(text: String): String = mapper.writeValueAsString(text)

    private fun createObject(workspaceId: Long, name: String): Long = graphQlTester.document(
        """mutation { createObject(input: { workspaceId: $workspaceId, name: "$name",
             properties: [{ name: "reference", kind: STRING }] }) { id } }""",
    ).execute().path("createObject.id").entity(Long::class.java).get()

    private fun createFunction(workspaceId: Long, name: String, returnObjectId: Long? = null): Long {
        val body = "export default function () { return {}; }"
        return graphQlTester.document(
            """
            mutation {
              createFunction(input: {
                workspaceId: $workspaceId, name: "$name",
                source: ${quote(body)}, typescript: ${quote(body)},
                returnType: ${if (returnObjectId != null) "OBJECT" else "MAP"}
                ${returnObjectId?.let { ", returnObjectId: $it" } ?: ""}
              }) { id }
            }
            """,
        ).execute().path("createFunction.id").entity(Long::class.java).get()
    }

    private fun createTool(workspaceId: Long, name: String): Long {
        // Takes the one default `input` the tool declares: code whose arity
        // disagrees with the declared parameters is refused at save now.
        val body = "export default function (input) { return {}; }"
        return graphQlTester.document(
            """
            mutation {
              createTool(input: {
                workspaceId: $workspaceId, name: "$name",
                source: ${quote(body)}, typescript: ${quote(body)}
              }) { id }
            }
            """,
        ).execute().path("createTool.id").entity(Long::class.java).get()
    }

    private fun createSkill(workspaceId: Long, name: String, catalog: String): Long {
        val catalogId = graphQlTester.document(
            """mutation { createSkillCatalog(workspaceId: $workspaceId, name: "$catalog") { id } }""",
        ).execute().path("createSkillCatalog.id").entity(Long::class.java).get()
        val content = "---\nname: $name\ndescription: What to do first.\n---\n\n# $name\n\nRead the ticket.\n"
        return graphQlTester.document(
            """
            mutation {
              createSkill(input: {
                workspaceId: $workspaceId, name: "$name", catalogId: $catalogId, content: ${quote(content)}
              }) { id }
            }
            """,
        ).execute().path("createSkill.id").entity(Long::class.java).get()
    }

    private companion object {
        const val CHANGED = "export default function () { return { changed: true }; }"
    }
}
