package io.mszymanski.orknux.server.recyclebin

import io.mszymanski.orknux.server.action.ActionSubtype
import io.mszymanski.orknux.server.action.ActionType
import io.mszymanski.orknux.server.action.ArgumentMapping
import io.mszymanski.orknux.server.action.WorkflowAction
import io.mszymanski.orknux.server.action.WorkflowActionRepository
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentSkillRepository
import io.mszymanski.orknux.server.agent.SkillCatalogRepository
import io.mszymanski.orknux.server.agent.AgentToolRepository
import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.workflow.MappingMode
import io.mszymanski.orknux.server.condition.ConditionCheck
import io.mszymanski.orknux.server.condition.ConditionProperty
import io.mszymanski.orknux.server.condition.ConditionType
import io.mszymanski.orknux.server.condition.WorkflowConditionRepository
import io.mszymanski.orknux.server.obj.InlineShapes
import io.mszymanski.orknux.server.obj.PropertyKind
import io.mszymanski.orknux.server.obj.WorkflowObjectRepository
import io.mszymanski.orknux.server.trigger.TriggerType
import io.mszymanski.orknux.server.trigger.WorkflowTriggerRepository
import io.mszymanski.orknux.server.variable.VariableCatalogRepository
import io.mszymanski.orknux.server.variable.VariableKind
import io.mszymanski.orknux.server.variable.VariableType
import io.mszymanski.orknux.server.variable.WorkspaceVariableRepository
import io.mszymanski.orknux.server.design.DesignImage
import io.mszymanski.orknux.server.design.DesignImageLayers
import io.mszymanski.orknux.server.design.DesignImageRepository
import io.mszymanski.orknux.server.design.LayerPixels
import io.mszymanski.orknux.server.design.WireframeRepository
import io.mszymanski.orknux.server.design.DesignDocumentRepository
import io.mszymanski.orknux.server.design.SheetRepository
import io.mszymanski.orknux.server.diagram.DiagramRepository
import io.mszymanski.orknux.server.issue.IssueAttachment
import io.mszymanski.orknux.server.issue.IssueAttachmentRepository
import io.mszymanski.orknux.server.issue.IssueLinkRepository
import io.mszymanski.orknux.server.issue.IssueRepository
import io.mszymanski.orknux.server.memory.MemoryCatalogRepository
import io.mszymanski.orknux.server.memory.MemoryRepository
import io.mszymanski.orknux.server.workflow.WorkflowRepository
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflowRepository
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
import java.util.concurrent.atomic.AtomicLong

/**
 * Deleting an object puts it in the workspace's recycle bin, and a restore
 * re-creates it.
 *
 * A delete writes the object down and removes it as before, so the bin is the
 * only new thing; a restore re-creates it with a new id - safe because a delete
 * is refused while anything still points at it - and each kind comes back whole
 * enough to assert on what it alone holds: a tool's parameters, a picture's
 * bytes, an issue's comments. A workflow is the exception, kept rather than
 * copied, and it is asserted that it is the same workflow that comes back.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class RecycleBinTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val entries: RecycleBinRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val tools: AgentToolRepository,
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val actions: WorkflowActionRepository,
    @Autowired val skills: AgentSkillRepository,
    @Autowired val skillCatalogs: SkillCatalogRepository,
    @Autowired val documents: DesignDocumentRepository,
    @Autowired val diagrams: DiagramRepository,
    @Autowired val sheets: SheetRepository,
    @Autowired val wireframes: WireframeRepository,
    @Autowired val memoryCatalogs: MemoryCatalogRepository,
    @Autowired val memories: MemoryRepository,
    @Autowired val designImages: DesignImageRepository,
    @Autowired val layers: DesignImageLayers,
    @Autowired val store: AttachmentStore,
    @Autowired val issues: IssueRepository,
    @Autowired val issueAttachments: IssueAttachmentRepository,
    @Autowired val issueLinks: IssueLinkRepository,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val assignments: WorkspaceWorkflowRepository,
    @Autowired val conditions: WorkflowConditionRepository,
    @Autowired val triggers: WorkflowTriggerRepository,
    @Autowired val objects: WorkflowObjectRepository,
    @Autowired val variables: WorkspaceVariableRepository,
    @Autowired val variableCatalogs: VariableCatalogRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0

    /** A workspace of its own per test, so nothing it does reaches another. */
    @BeforeEach
    fun reset() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "recycle-${counter.incrementAndGet()}")).id)
    }

    /** This workspace's bin, and nobody else's. */
    private fun binned() = entries.findAll().filter { it.workspaceId == workspaceId }

    // ------------------------------------------------------------- the six kinds

    /** An agent comes back whole: the delete put it in the bin, the restore re-made it. */
    @Test
    fun `a deleted agent goes to the bin and restores with its prompt`() {
        val id = agent("Researcher", prompt = "Read the tests first.")
        deleteAgent(id)

        assertThat(agents.findById(id)).isEmpty
        val entry = binned().single()
        assertThat(entry.kind).isEqualTo(RecycleBinKind.AGENT)
        assertThat(entry.name).isEqualTo("Researcher")
        assertThat(entry.deletedBy).isEqualTo("alice")

        restore(entry.id!!)

        assertThat(binned()).isEmpty()
        val back = agents.findByWorkspaceIdAndName(workspaceId, "Researcher")
        assertThat(back).isNotNull
        assertThat(back!!.systemPrompt).isEqualTo("Read the tests first.")
        // A new id, not the old one: nothing referenced it, so nothing is left dangling.
        assertThat(back.id).isNotEqualTo(id)
    }

    /** A tool's parameters are in the snapshot, so its signature survives the trip. */
    @Test
    fun `a deleted tool restores with its parameters`() {
        val id = tool("forecast")
        graphQlTester.document(
            """mutation { updateTool(id: $id, input: {
                 params: [{ name: "city", type: STRING }, { name: "days", type: NUMBER }],
                 source: "export default function forecast(city, days) { return {}; }",
                 typescript: "export default function forecast(city: string, days: number) { return {}; }"
               }) { signature } }""",
        ).execute().path("updateTool.signature").entity(String::class.java).isEqualTo("(city: string, days: number)")

        deleteTool(id)
        restore(binned().single().id!!)

        val back = requireNotNull(tools.findByWorkspaceIdAndName(workspaceId, "forecast"))
        assertThat(back.params.map { it.name }).containsExactly("city", "days")
    }

    /** A function restores, and a workspace function is editable again. */
    @Test
    fun `a deleted function goes to the bin and restores`() {
        val id = function("greet")
        deleteFunction(id)
        assertThat(binned().single().kind).isEqualTo(RecycleBinKind.FUNCTION)

        restore(binned().single().id!!)
        val back = requireNotNull(functions.findByWorkspaceIdAndName(workspaceId, "greet"))
        assertThat(back.editable).isTrue()
    }

    /** A skill restores into its folder with a fresh, usable id key. */
    @Test
    fun `a deleted skill restores into its catalog`() {
        val catalogId = graphQlTester.document(
            """mutation { createSkillCatalog(workspaceId: $workspaceId, name: "reviews") { id } }""",
        ).execute().path("createSkillCatalog.id").entity(Long::class.java).get()
        val id = graphQlTester.document(
            """mutation { createSkill(input: { workspaceId: $workspaceId, name: "reviewCode", catalogId: $catalogId }) { id } }""",
        ).execute().path("createSkill.id").entity(Long::class.java).get()

        deleteSkill(id)
        restore(binned().single().id!!)

        val back = requireNotNull(skills.findByWorkspaceIdAndName(workspaceId, "reviewCode"))
        assertThat(back.catalogId).isEqualTo(catalogId)
        assertThat(back.key).isNotBlank()
    }

    /** A skill catalog deleted with what it held is one entry, and comes back as that folder with its skills in it. */
    @Test
    fun `a deleted skill catalog goes to the bin with its skills and restores as the folder it was`() {
        val catalogId = graphQlTester.document(
            """mutation { createSkillCatalog(workspaceId: $workspaceId, name: "scratch") { id } }""",
        ).execute().path("createSkillCatalog.id").entity(Long::class.java).get()
        listOf("first", "second").forEach { name ->
            graphQlTester.document(
                """mutation { createSkill(input: { workspaceId: $workspaceId, name: "$name", catalogId: $catalogId }) { id } }""",
            ).execute().path("createSkill.id").entity(Long::class.java).get()
        }

        del("deleteSkillCatalog", catalogId)
        val entry = binned().single()
        assertThat(entry.kind).isEqualTo(RecycleBinKind.SKILL_CATALOG)
        assertThat(entry.name).isEqualTo("scratch")
        assertThat(skills.findByWorkspaceIdAndName(workspaceId, "first")).isNull()

        restore(entry.id!!)
        val folder = requireNotNull(skillCatalogs.findByWorkspaceIdAndName(workspaceId, "scratch"))
        val back = listOf("first", "second").map { requireNotNull(skills.findByWorkspaceIdAndName(workspaceId, it)) }
        assertThat(back.map { it.catalogId }).containsOnly(folder.id)
        assertThat(back.map { it.key }).allSatisfy { assertThat(it).isNotBlank() }
        assertThat(binned()).isEmpty()
    }

    /** An empty skill catalog is a thing somebody named: it is binned too, and refused while its name is taken. */
    @Test
    fun `an empty skill catalog goes to the bin, and is not restored over one of its name`() {
        val catalogId = graphQlTester.document(
            """mutation { createSkillCatalog(workspaceId: $workspaceId, name: "empty") { id } }""",
        ).execute().path("createSkillCatalog.id").entity(Long::class.java).get()
        del("deleteSkillCatalog", catalogId)
        val entry = binned().single()
        assertThat(entry.kind).isEqualTo(RecycleBinKind.SKILL_CATALOG)

        graphQlTester.document(
            """mutation { createSkillCatalog(workspaceId: $workspaceId, name: "empty") { id } }""",
        ).execute().path("createSkillCatalog.id").entity(Long::class.java).get()
        graphQlTester.document("mutation { restoreRecycleBinEntry(id: ${entry.id}) }").execute()
            .errors().satisfy { errors -> assertThat(errors).isNotEmpty() }
        assertThat(binned()).hasSize(1)
    }

    /** A document goes to the bin and restores, re-filed in its catalog. */
    @Test
    fun `a deleted document goes to the bin and restores`() {
        val id = graphQlTester.document(
            """mutation { createDocument(workspaceId: $workspaceId, name: "Spec") { id } }""",
        ).execute().path("createDocument.id").entity(Long::class.java).get()
        val catalog = requireNotNull(documents.findById(id).orElse(null)).catalogId

        deleteDocument(id)
        assertThat(binned().single().kind).isEqualTo(RecycleBinKind.DOCUMENT)

        restore(binned().single().id!!)
        val back = documents.findByWorkspaceIdOrderByUpdatedAtDescIdDesc(workspaceId).single()
        assertThat(back.name).isEqualTo("Spec")
        assertThat(back.catalogId).isEqualTo(catalog)
    }

    /** A diagram goes to the bin and restores. */
    @Test
    fun `a deleted diagram goes to the bin and restores`() {
        val id = graphQlTester.document(
            """mutation { createDiagram(workspaceId: $workspaceId, name: "Flow") { id } }""",
        ).execute().path("createDiagram.id").entity(Long::class.java).get()

        deleteDiagram(id)
        assertThat(binned().single().kind).isEqualTo(RecycleBinKind.DIAGRAM)

        restore(binned().single().id!!)
        assertThat(diagrams.findAll().filter { it.workspaceId == workspaceId }.map { it.name }).contains("Flow")
    }

    /** A sheet goes to the bin and restores with its workbook. */
    @Test
    fun `a deleted sheet goes to the bin and restores with its workbook`() {
        val id = graphQlTester.document(
            """mutation { createSheet(workspaceId: $workspaceId, name: "Budget", workbook: "{\"tabs\":[{\"name\":\"Sheet1\",\"cells\":{\"A1\":\"Total\"}}]}") { id } }""",
        ).execute().path("createSheet.id").entity(Long::class.java).get()

        graphQlTester.document("""mutation { deleteSheet(id: $id) }""")
            .execute().path("deleteSheet").entity(Boolean::class.java).isEqualTo(true)
        assertThat(binned().single().kind).isEqualTo(RecycleBinKind.SHEET)

        restore(binned().single().id!!)
        val back = sheets.findByWorkspaceIdOrderByUpdatedAtDescIdDesc(workspaceId).single()
        assertThat(back.name).isEqualTo("Budget")
        assertThat(back.workbook).contains("Total")
    }

    /** An action goes to the bin and restores with its settings and its mappings. */
    @Test
    fun `a deleted action goes to the bin and restores with its settings`() {
        val id = actions.save(
            WorkflowAction(
                workspaceId = workspaceId,
                name = "Notify",
                type = ActionType.EXECUTE,
                subtype = ActionSubtype.HTTP_REQUEST,
                url = "https://example.invalid/hook",
                method = "POST",
                content = "{\"hello\":true}",
                mappings = mutableListOf(ArgumentMapping("to", "input.user")),
            ),
        ).id!!

        graphQlTester.document("""mutation { deleteAction(id: $id) }""")
            .execute().path("deleteAction").entity(Boolean::class.java).isEqualTo(true)
        assertThat(binned().single().kind).isEqualTo(RecycleBinKind.ACTION)

        restore(binned().single().id!!)
        val back = requireNotNull(actions.findByWorkspaceIdAndName(workspaceId, "Notify"))
        assertThat(back.id).isNotEqualTo(id)
        assertThat(back.subtype).isEqualTo(ActionSubtype.HTTP_REQUEST)
        assertThat(back.url).isEqualTo("https://example.invalid/hook")
        assertThat(back.method).isEqualTo("POST")
        assertThat(back.content).isEqualTo("{\"hello\":true}")
        assertThat(back.mappings.map { it.argument to it.expression }).containsExactly("to" to "input.user")
    }

    // ---------------------------------------- what an action sits beside, and what it names

    /** A condition comes back asking what it asked. */
    @Test
    fun `a deleted condition goes to the bin and restores with what it asks`() {
        val id = condition("""name: "Urgent", type: SLACK, property: MESSAGE_TEXT, check: CONTAINS, negate: true, values: ["urgent", "asap"], icon: "flame"""")
        del("deleteCondition", id)

        assertThat(conditions.findById(id)).isEmpty
        val entry = binned().single()
        assertThat(entry.kind).isEqualTo(RecycleBinKind.CONDITION)
        assertThat(entry.name).isEqualTo("Urgent")

        restore(entry.id!!)
        val back = requireNotNull(conditions.findByWorkspaceIdAndWorkflowIdIsNullAndName(workspaceId, "Urgent"))
        assertThat(back.id).isNotEqualTo(id)
        assertThat(back.type).isEqualTo(ConditionType.SLACK)
        assertThat(back.property).isEqualTo(ConditionProperty.MESSAGE_TEXT)
        assertThat(back.check).isEqualTo(ConditionCheck.CONTAINS)
        assertThat(back.negate).isTrue()
        assertThat(back.values).containsExactly("urgent", "asap")
        assertThat(back.icon).isEqualTo("flame")
        assertThat(binned()).isEmpty()
    }

    /** A function condition restores onto its function, arguments and all. */
    @Test
    fun `a function condition restores with its function and its arguments`() {
        val function = booleanFunction("isVip")
        val id = condition(
            """name: "Vip", type: FUNCTION, functionId: $function, arguments: [{ name: "who", expression: "input.user", mode: REFERENCE }]""",
        )
        del("deleteCondition", id)

        restore(binned().single().id!!)
        val back = requireNotNull(conditions.findByWorkspaceIdAndWorkflowIdIsNullAndName(workspaceId, "Vip"))
        assertThat(back.functionId).isEqualTo(function)
        assertThat(back.arguments.map { Triple(it.name, it.expression, it.mode) })
            .containsExactly(Triple("who", "input.user", MappingMode.REFERENCE))
    }

    /**
     * A function deleted while its condition was in the bin, and restored, has
     * a new id - and the condition finds it by name. Before it is back, the
     * restore is refused saying which function is missing.
     */
    @Test
    fun `a condition whose function went to the bin too is refused until the function is back`() {
        val function = booleanFunction("isVip")
        del("deleteCondition", condition("""name: "Vip", type: FUNCTION, functionId: $function"""))
        del("deleteFunction", function)
        val entry = binned().single { it.kind == RecycleBinKind.CONDITION }

        graphQlTester.document("""mutation { restoreRecycleBinEntry(id: ${entry.id}) }""")
            .execute().errors().satisfy { errors ->
                assertThat(errors).singleElement().satisfies({
                    assertThat(it.message).contains("the function isVip")
                    assertThat(it.extensions["code"]).isEqualTo("RecycleBinReferenceGone")
                    assertThat(it.extensions["arguments"]).isEqualTo(
                        mapOf("kind" to "condition", "name" to "Vip", "missing" to "the function isVip", "missingName" to "isVip"),
                    )
                })
            }
        assertThat(binned()).hasSize(2)

        restore(binned().single { it.kind == RecycleBinKind.FUNCTION }.id!!)
        restore(entry.id!!)
        val back = requireNotNull(conditions.findByWorkspaceIdAndWorkflowIdIsNullAndName(workspaceId, "Vip"))
        val again = requireNotNull(functions.findByWorkspaceIdAndName(workspaceId, "isVip"))
        assertThat(again.id).isNotEqualTo(function)
        assertThat(back.functionId).isEqualTo(again.id)
    }

    /** A composite comes back made of the same conditions. */
    @Test
    fun `a composite condition restores with its members`() {
        val first = condition("""name: "A", type: SLACK, property: MESSAGE_TEXT, check: CONTAINS, values: ["a"]""")
        val second = condition("""name: "B", type: SLACK, property: MESSAGE_TEXT, check: CONTAINS, values: ["b"]""")
        del("deleteCondition", condition("""name: "Either", type: ANY_OF, members: [$first, $second]"""))

        restore(binned().single().id!!)
        val back = requireNotNull(conditions.findByWorkspaceIdAndWorkflowIdIsNullAndName(workspaceId, "Either"))
        assertThat(back.type).isEqualTo(ConditionType.ANY_OF)
        assertThat(back.members).containsExactlyInAnyOrder(first, second)
    }

    /** A condition is not restored over one that has taken its name. */
    @Test
    fun `a condition is not restored onto a taken name`() {
        val asked = """name: "Urgent", type: SLACK, property: MESSAGE_TEXT, check: CONTAINS, values: ["urgent"]"""
        del("deleteCondition", condition(asked))
        condition(asked)

        graphQlTester.document("""mutation { restoreRecycleBinEntry(id: ${binned().single().id}) }""")
            .execute().errors().satisfy { errors ->
                assertThat(errors).singleElement().satisfies({ assertThat(it.extensions["code"]).isEqualTo("ConditionNameTaken") })
            }
        assertThat(binned()).hasSize(1)
    }

    /** An object comes back with every property, the ones defined in place included. */
    @Test
    fun `a deleted object goes to the bin and restores with its properties`() {
        val address = shape("Address", """[{ name: "city", kind: STRING }]""")
        val id = shape(
            "Customer",
            """[
                { name: "name", kind: STRING, description: "As on the invoice" },
                { name: "tags", kind: ARRAY, elementKind: STRING },
                { name: "home", kind: OBJECT, refObjectId: $address },
                { name: "contact", kind: OBJECT, properties: [
                    { name: "email", kind: STRING },
                    { name: "office", kind: OBJECT, refObjectId: $address }
                ] }
            ]""",
            description = "Who pays",
        )
        del("deleteObject", id)

        assertThat(objects.findById(id)).isEmpty
        val entry = binned().single()
        assertThat(entry.kind).isEqualTo(RecycleBinKind.OBJECT)
        assertThat(entry.name).isEqualTo("Customer")

        restore(entry.id!!)
        val back = requireNotNull(objects.findByWorkspaceIdAndName(workspaceId, "Customer"))
        assertThat(back.id).isNotEqualTo(id)
        assertThat(back.description).isEqualTo("Who pays")
        assertThat(back.createdBy).isEqualTo("alice")
        assertThat(back.properties.map { it.name }).containsExactly("name", "tags", "home", "contact")
        assertThat(back.properties[0].description).isEqualTo("As on the invoice")
        assertThat(back.properties[1].elementKind).isEqualTo(PropertyKind.STRING)
        assertThat(back.properties[2].refObjectId).isEqualTo(address)
        val contact = requireNotNull(InlineShapes.read(back.properties[3].inlineProperties))
        assertThat(contact.map { it.name }).containsExactly("email", "office")
        assertThat(contact[1].refObjectId).isEqualTo(address)
        assertThat(binned()).isEmpty()
    }

    /**
     * What an object points at is found again by name: the other object was
     * deleted after it and restored before it, so it has a new id; and a
     * property pointing at the object itself follows it to its own new id.
     */
    @Test
    fun `an object's references follow what they pointed at`() {
        val address = shape("Address", """[{ name: "city", kind: STRING }]""")
        val id = shape("Node", """[{ name: "label", kind: STRING }]""")
        graphQlTester.document(
            """mutation { updateObject(id: $id, input: { name: "Node", properties: [
                { name: "label", kind: STRING },
                { name: "parent", kind: OBJECT, refObjectId: $id },
                { name: "where", kind: OBJECT, refObjectId: $address },
                { name: "more", kind: OBJECT, properties: [{ name: "children", kind: ARRAY, elementKind: OBJECT, refObjectId: $id }] }
            ] }) { id } }""",
        ).execute().path("updateObject.id").entity(Long::class.java).isEqualTo(id)
        del("deleteObject", id)
        del("deleteObject", address)
        val entry = binned().single { it.name == "Node" }

        graphQlTester.document("""mutation { restoreRecycleBinEntry(id: ${entry.id}) }""")
            .execute().errors().satisfy { errors ->
                assertThat(errors).singleElement().satisfies({
                    assertThat(it.message).contains("the object Address")
                    assertThat(it.extensions["code"]).isEqualTo("RecycleBinReferenceGone")
                })
            }
        assertThat(objects.findByWorkspaceIdAndName(workspaceId, "Node")).isNull()

        restore(binned().single { it.name == "Address" }.id!!)
        restore(entry.id!!)
        val back = requireNotNull(objects.findByWorkspaceIdAndName(workspaceId, "Node"))
        val there = requireNotNull(objects.findByWorkspaceIdAndName(workspaceId, "Address"))
        assertThat(there.id).isNotEqualTo(address)
        assertThat(back.properties.single { it.name == "parent" }.refObjectId).isEqualTo(back.id)
        assertThat(back.properties.single { it.name == "where" }.refObjectId).isEqualTo(there.id)
        val more = requireNotNull(InlineShapes.read(back.properties.single { it.name == "more" }.inlineProperties))
        assertThat(more.single().refObjectId).isEqualTo(back.id)
    }

    /** A scheduled trigger comes back with its clock, its payload and its switch as it was. */
    @Test
    fun `a deleted trigger goes to the bin and restores as it was`() {
        val asks = condition("""name: "Urgent", type: SLACK, property: MESSAGE_TEXT, check: CONTAINS, values: ["urgent"]""")
        val id = trigger(
            """name: "Nightly", type: SCHEDULED, cron: "0 3 * * *", timezone: "Europe/Warsaw", payload: "{\"kind\":\"nightly\"}", conditionId: $asks, enabled: false, icon: "moon"""",
        )
        del("deleteTrigger", id)

        assertThat(triggers.findById(id)).isEmpty
        val entry = binned().single()
        assertThat(entry.kind).isEqualTo(RecycleBinKind.TRIGGER)
        assertThat(entry.name).isEqualTo("Nightly")

        restore(entry.id!!)
        val back = requireNotNull(triggers.findByWorkspaceIdAndWorkflowIdIsNullAndName(workspaceId, "Nightly"))
        assertThat(back.id).isNotEqualTo(id)
        assertThat(back.type).isEqualTo(TriggerType.SCHEDULED)
        assertThat(back.cron).isEqualTo("0 3 * * *")
        assertThat(back.timezone).isEqualTo("Europe/Warsaw")
        assertThat(back.payload).isEqualTo("{\"kind\":\"nightly\"}")
        assertThat(back.conditionId).isEqualTo(asks)
        assertThat(back.enabled).isFalse()
        assertThat(back.icon).isEqualTo("moon")
        assertThat(back.lastFiredAt).isNull()
    }

    /**
     * A webhook trigger restores onto its path and its object, and is refused -
     * as a save would be - once another trigger answers on that path.
     */
    @Test
    fun `a webhook trigger restores with its path and its object, unless the path is taken`() {
        val body = shape("Order", """[{ name: "id", kind: STRING }]""")
        val path = "orders-${counter.incrementAndGet()}"
        val asked = """type: WEBHOOK, webhookPath: "$path", objectId: $body"""
        del("deleteTrigger", trigger("""name: "Orders", $asked"""))

        restore(binned().single().id!!)
        val back = requireNotNull(triggers.findByWorkspaceIdAndWorkflowIdIsNullAndName(workspaceId, "Orders"))
        assertThat(back.webhookPath).isEqualTo(path)
        assertThat(back.objectId).isEqualTo(body)

        del("deleteTrigger", back.id!!)
        trigger("""name: "Orders again", $asked""")
        graphQlTester.document("""mutation { restoreRecycleBinEntry(id: ${binned().single().id}) }""")
            .execute().errors().satisfy { errors ->
                assertThat(errors).singleElement().satisfies({ assertThat(it.extensions["code"]).isEqualTo("TriggerWebhookPathTaken") })
            }
        assertThat(binned()).hasSize(1)
    }

    /** A trigger whose condition has gone is refused, saying so, rather than restored to fire on everything. */
    @Test
    fun `a trigger whose condition is gone is refused`() {
        val asks = condition("""name: "Urgent", type: SLACK, property: MESSAGE_TEXT, check: CONTAINS, values: ["urgent"]""")
        del("deleteTrigger", trigger("""name: "Nightly", type: SCHEDULED, cron: "0 3 * * *", conditionId: $asks"""))
        del("deleteCondition", asks)
        val entry = binned().single { it.kind == RecycleBinKind.TRIGGER }

        graphQlTester.document("""mutation { restoreRecycleBinEntry(id: ${entry.id}) }""")
            .execute().errors().satisfy { errors ->
                assertThat(errors).singleElement().satisfies({ assertThat(it.message).contains("the condition Urgent") })
            }

        restore(binned().single { it.kind == RecycleBinKind.CONDITION }.id!!)
        restore(entry.id!!)
        val back = requireNotNull(triggers.findByWorkspaceIdAndWorkflowIdIsNullAndName(workspaceId, "Nightly"))
        assertThat(back.conditionId)
            .isEqualTo(conditions.findByWorkspaceIdAndWorkflowIdIsNullAndName(workspaceId, "Urgent")?.id)
    }

    /**
     * A secret comes back with its value - and that value is never in the bin in
     * the clear, which is the whole of why a variable's snapshot is not written
     * the way the others are.
     */
    @Test
    fun `a deleted variable goes to the bin encrypted and restores with its value`() {
        val catalog = variableCatalog("Keys")
        val id = variable(catalog, """name: "apiKey", description: "For the billing API", type: STRING, kind: SECRET, value: "s3cret-value"""")
        del("deleteVariable", id)

        assertThat(variables.findById(id)).isEmpty
        val entry = binned().single()
        assertThat(entry.kind).isEqualTo(RecycleBinKind.VARIABLE)
        assertThat(entry.name).isEqualTo("apiKey")
        assertThat(entry.snapshot).doesNotContain("s3cret-value").contains("orkx1:")

        restore(entry.id!!)
        val back = requireNotNull(variables.findByCatalogIdAndName(catalog, "apiKey"))
        assertThat(back.id).isNotEqualTo(id)
        assertThat(back.value).isEqualTo("s3cret-value")
        assertThat(back.kind).isEqualTo(VariableKind.SECRET)
        assertThat(back.description).isEqualTo("For the billing API")
        assertThat(binned()).isEmpty()
    }

    /** Its catalog deleted since - it can be, once empty - a variable brings the folder back with it. */
    @Test
    fun `a variable whose catalog is gone restores into one of that name`() {
        val catalog = variableCatalog("Limits")
        del("deleteVariable", variable(catalog, """name: "maxRows", type: NUMBER, kind: VALUE, value: "500""""))
        del("deleteVariableCatalog", catalog)

        restore(binned().single().id!!)
        val again = requireNotNull(variableCatalogs.findByWorkspaceIdAndName(workspaceId, "Limits"))
        assertThat(again.id).isNotEqualTo(catalog)
        val back = requireNotNull(variables.findByCatalogIdAndName(again.id!!, "maxRows"))
        assertThat(back.value).isEqualTo("500")
        assertThat(back.type).isEqualTo(VariableType.NUMBER)
        assertThat(back.kind).isEqualTo(VariableKind.VALUE)
    }

    /** A variable is not restored over one that has taken its name in that catalog. */
    @Test
    fun `a variable is not restored onto a taken name`() {
        val catalog = variableCatalog("Keys")
        del("deleteVariable", variable(catalog, """name: "apiKey", type: STRING, value: "old""""))
        variable(catalog, """name: "apiKey", type: STRING, value: "new"""")

        graphQlTester.document("""mutation { restoreRecycleBinEntry(id: ${binned().single().id}) }""")
            .execute().errors().satisfy { errors ->
                assertThat(errors).singleElement().satisfies({ assertThat(it.extensions["code"]).isEqualTo("VariableNameTaken") })
            }
        assertThat(variables.findByCatalogIdAndName(catalog, "apiKey")?.value).isEqualTo("new")
    }

    /**
     * An action whose function was deleted after it, and restored before it,
     * comes back calling that function under its new id. The column is a foreign
     * key, so this used to be an internal error rather than a restore.
     */
    @Test
    fun `an action restores onto its function after the function has been through the bin`() {
        val function = function("notify")
        val id = actions.save(
            WorkflowAction(
                workspaceId = workspaceId,
                name = "Notify",
                type = ActionType.EXECUTE,
                subtype = ActionSubtype.FUNCTION,
                functionId = function,
            ),
        ).id!!
        del("deleteAction", id)
        del("deleteFunction", function)
        val entry = binned().single { it.kind == RecycleBinKind.ACTION }

        graphQlTester.document("""mutation { restoreRecycleBinEntry(id: ${entry.id}) }""")
            .execute().errors().satisfy { errors ->
                assertThat(errors).singleElement().satisfies({
                    assertThat(it.message).contains("the function notify")
                    assertThat(it.extensions["code"]).isEqualTo("RecycleBinReferenceGone")
                })
            }

        restore(binned().single { it.kind == RecycleBinKind.FUNCTION }.id!!)
        restore(entry.id!!)
        val back = requireNotNull(actions.findByWorkspaceIdAndName(workspaceId, "Notify"))
        assertThat(back.functionId).isEqualTo(functions.findByWorkspaceIdAndName(workspaceId, "notify")?.id)
        assertThat(back.functionId).isNotEqualTo(function)
    }

    // ------------------------------------------------- what is not only a row

    /** A wireframe goes to the bin and restores with its drawing. */
    @Test
    fun `a deleted wireframe goes to the bin and restores with its drawing`() {
        val id = graphQlTester.document(
            """mutation { createWireframe(workspaceId: $workspaceId, name: "Sign in", document: "{\"elements\":[{\"id\":\"a\",\"type\":\"text\",\"text\":\"Welcome back\",\"x\":0,\"y\":0,\"w\":200,\"h\":30}]}") { id } }""",
        ).execute().path("createWireframe.id").entity(Long::class.java).get()
        val catalog = requireNotNull(wireframes.findById(id).orElse(null)).catalogId

        del("deleteWireframe", id)
        assertThat(wireframes.findById(id)).isEmpty
        assertThat(binned().single().kind).isEqualTo(RecycleBinKind.WIREFRAME)

        restore(binned().single().id!!)
        val back = wireframes.findAll().single { it.workspaceId == workspaceId }
        assertThat(back.name).isEqualTo("Sign in")
        assertThat(back.document).contains("Welcome back")
        assertThat(back.catalogId).isEqualTo(catalog)
    }

    /** A memory goes to the bin and comes back in its catalog, still its author's. */
    @Test
    fun `a deleted memory goes to the bin and restores into its catalog`() {
        val catalogId = memoryCatalog("decisions")
        val id = memory(catalogId, "Postgres first", "SQLite is the second engine, not the first.")

        del("deleteMemory", id)
        assertThat(memories.findById(id)).isEmpty
        val entry = binned().single()
        assertThat(entry.kind).isEqualTo(RecycleBinKind.MEMORY)
        assertThat(entry.name).isEqualTo("Postgres first")

        restore(entry.id!!)
        val back = requireNotNull(memories.findByCatalogIdAndTitle(catalogId, "Postgres first"))
        assertThat(back.content).isEqualTo("SQLite is the second engine, not the first.")
        assertThat(back.createdBy).isEqualTo("alice")
        assertThat(binned()).isEmpty()
    }

    /** A memory catalog deleted with what it held is one entry, and comes back as that folder with its memories in it. */
    @Test
    fun `a deleted memory catalog goes to the bin with its memories and restores as the folder it was`() {
        val catalogId = memoryCatalog("scratch")
        memory(catalogId, "One", "first")
        memory(catalogId, "Two", "second")
        // Every new catalog is granted to the workspace's agents, and this workspace has none, so it may go.
        del("deleteMemoryCatalog", catalogId)

        val entry = binned().single()
        assertThat(entry.kind).isEqualTo(RecycleBinKind.MEMORY_CATALOG)
        assertThat(entry.name).isEqualTo("scratch")
        assertThat(memoryCatalogs.findByWorkspaceIdAndName(workspaceId, "scratch")).isNull()

        restore(entry.id!!)
        val folder = requireNotNull(memoryCatalogs.findByWorkspaceIdAndName(workspaceId, "scratch"))
        assertThat(folder.isDefault).isFalse()
        val back = memories.findByCatalogIdInOrderByLastModifiedAtDesc(listOf(folder.id!!))
        assertThat(back.map { it.title }).containsExactlyInAnyOrder("One", "Two")
        assertThat(back.single { it.title == "One" }.content).isEqualTo("first")
        assertThat(back.map { it.createdBy }).containsOnly("alice")
        assertThat(binned()).isEmpty()
    }

    /**
     * An empty catalog is binned too - it is what the report was about - and
     * the agents that were granted it, which lose the grant when it goes, have
     * it again when it comes back.
     */
    @Test
    fun `an empty memory catalog goes to the bin, and restored its agents are granted it again`() {
        val agentId = agent("keeper")
        val catalogId = memoryCatalog("notes")
        // Read through the query the delete itself asks: the grants are a collection no test thread may open.
        val granted = { agents.findGrantedMemoryCatalog(workspaceId, "notes").map { it.id } }
        assertThat(granted()).containsExactly(agentId)

        del("deleteMemoryCatalog", catalogId)
        val entry = binned().single()
        assertThat(entry.kind).isEqualTo(RecycleBinKind.MEMORY_CATALOG)
        assertThat(entry.name).isEqualTo("notes")
        assertThat(granted()).isEmpty()

        restore(entry.id!!)
        assertThat(memoryCatalogs.findByWorkspaceIdAndName(workspaceId, "notes")).isNotNull()
        assertThat(granted()).containsExactly(agentId)
        assertThat(binned()).isEmpty()
    }

    /** Two folders of one name cannot be told apart by a grant, so a restore over a taken name is refused and kept. */
    @Test
    fun `a memory catalog is not restored over one of its name`() {
        del("deleteMemoryCatalog", memoryCatalog("taken"))
        val entry = binned().single()
        memoryCatalog("taken")
        graphQlTester.document("mutation { restoreRecycleBinEntry(id: ${entry.id}) }").execute()
            .errors().satisfy { errors -> assertThat(errors).isNotEmpty() }
        assertThat(binned()).hasSize(1)
    }

    /** A picture goes to the bin with its bytes and its layers, since the delete removes the file. */
    @Test
    fun `a deleted picture goes to the bin and restores with its bytes and layers`() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7)
        val image = designImages.save(
            DesignImage(
                workspaceId = workspaceId,
                modelId = null,
                modelName = "drawer",
                prompt = "a lighthouse",
                size = null,
                quality = null,
                style = null,
                filename = "lighthouse.png",
                contentType = "image/png",
                sizeBytes = bytes.size.toLong(),
                location = store.put(workspaceId, "lighthouse.png", bytes),
                drawnBy = "alice",
                name = "Lighthouse",
            ),
        )
        val artifact = "DESIGN-${image.id}"
        layers.keep(workspaceId, artifact, listOf(LayerPixels("sky", true, false, byteArrayOf(9, 8, 7))))
        val was = image.location

        graphQlTester.document("""mutation { deleteArtifact(id: "$artifact") }""")
            .execute().path("deleteArtifact").entity(Boolean::class.java).isEqualTo(true)
        assertThat(designImages.findById(image.id!!)).isEmpty
        assertThat(store.exists(was)).isFalse()
        val entry = binned().single()
        assertThat(entry.kind).isEqualTo(RecycleBinKind.IMAGE)
        assertThat(entry.name).isEqualTo("Lighthouse")

        restore(entry.id!!)
        val back = designImages.findAll().single { it.workspaceId == workspaceId }
        assertThat(back.name).isEqualTo("Lighthouse")
        assertThat(back.prompt).isEqualTo("a lighthouse")
        assertThat(store.open(back.location).use { it.readBytes() }).isEqualTo(bytes)
        val drawn = layers.pixelsOf(workspaceId, "DESIGN-${back.id}")
        assertThat(drawn.map { it.name }).containsExactly("sky")
        assertThat(drawn.single().png).isEqualTo(byteArrayOf(9, 8, 7))
    }

    /** An issue comes back with what hung on it: its number, comments, labels, links and files. */
    @Test
    fun `a deleted issue goes to the bin and restores with its comments and files`() {
        val id = graphQlTester.document(
            """mutation { createIssue(input: { workspaceId: $workspaceId, title: "Login loops", description: "It never ends.", labels: ["auth"] }) { id } }""",
        ).execute().path("createIssue.id").entity(Long::class.java).get()
        val number = requireNotNull(issues.findById(id).orElse(null)).number
        graphQlTester.document("""mutation { commentOnIssue(id: $id, content: "Seen on Firefox too.") { id } }""")
            .execute().path("commentOnIssue.id").entity(Long::class.java).isEqualTo(id)
        graphQlTester.document("""mutation { addIssueLink(id: $id, url: "https://example.invalid/trace", title: "Trace") { id } }""")
            .execute().path("addIssueLink.id").entity(Long::class.java).isEqualTo(id)
        val bytes = "a screenshot".toByteArray()
        val attached = issueAttachments.save(
            IssueAttachment(
                workspaceId = workspaceId,
                issueId = id,
                filename = "loop.png",
                contentType = "image/png",
                sizeBytes = bytes.size.toLong(),
                location = store.put(workspaceId, "loop.png", bytes),
                uploadedBy = "alice",
            ),
        )

        del("deleteIssue", id)
        assertThat(issues.findById(id)).isEmpty
        assertThat(store.exists(attached.location)).isFalse()
        val entry = binned().single()
        assertThat(entry.kind).isEqualTo(RecycleBinKind.ISSUE)
        assertThat(entry.name).isEqualTo("#$number Login loops")

        restore(entry.id!!)
        val back = requireNotNull(issues.findByWorkspaceIdAndNumber(workspaceId, number))
        assertThat(back.id).isNotEqualTo(id)
        assertThat(back.title).isEqualTo("Login loops")
        assertThat(back.description).isEqualTo("It never ends.")
        assertThat(back.labels).containsExactly("auth")
        assertThat(back.reporter).isEqualTo("alice")
        graphQlTester.document("""query { workspaceIssue(workspaceId: $workspaceId, number: $number) { comments { content } } }""")
            .execute().path("workspaceIssue.comments[*].content").entityList(String::class.java).containsExactly("Seen on Firefox too.")
        assertThat(issueLinks.findByIssueIdOrderByAddedAtAscIdAsc(back.id!!).map { it.url })
            .containsExactly("https://example.invalid/trace")
        val file = issueAttachments.findByIssueIdOrderByUploadedAtAsc(back.id!!).single()
        assertThat(file.filename).isEqualTo("loop.png")
        assertThat(store.open(file.location).use { it.readBytes() }).isEqualTo(bytes)
    }

    /** An issue put back after its number was taken gets the next one rather than refusing. */
    @Test
    fun `an issue restored onto a taken number takes the next one`() {
        val id = issue("First")
        val number = requireNotNull(issues.findById(id).orElse(null)).number
        del("deleteIssue", id)
        // The newest number is free again, so the next issue takes it.
        val since = issue("Second")
        assertThat(requireNotNull(issues.findById(since).orElse(null)).number).isEqualTo(number)

        restore(binned().single().id!!)
        val back = issues.findAll().single { it.workspaceId == workspaceId && it.title == "First" }
        assertThat(back.number).isEqualTo(number + 1)
    }

    /** A workflow is taken off the workspace into the bin, and put back it is the same workflow. */
    @Test
    fun `a removed workflow goes to the bin and restores as the same workflow`() {
        val (assignmentId, workflowId) = workflow("Onboarding")

        del("removeWorkflow", assignmentId)
        assertThat(assignments.existsByWorkspaceIdAndWorkflowId(workspaceId, workflowId)).isFalse()
        val entry = binned().single()
        assertThat(entry.kind).isEqualTo(RecycleBinKind.WORKFLOW)
        assertThat(entry.name).isEqualTo("Onboarding")
        // Kept whole while the bin holds it: nothing was copied, so nothing can have been left out.
        assertThat(workflows.findById(workflowId)).isPresent

        restore(entry.id!!)
        assertThat(assignments.existsByWorkspaceIdAndWorkflowId(workspaceId, workflowId)).isTrue()
        assertThat(binned()).isEmpty()
        graphQlTester.document("""query { workspaceWorkflows(workspaceId: $workspaceId) { content { name workflowId } } }""")
            .execute().path("workspaceWorkflows.content[*].name").entityList(String::class.java).containsExactly("Onboarding")
    }

    /** Deleted from the bin, a workflow is really deleted: that is the delete the bin was holding back. */
    @Test
    fun `purging a binned workflow deletes its definition`() {
        val (assignmentId, workflowId) = workflow("Throwaway")
        del("removeWorkflow", assignmentId)

        graphQlTester.document("""mutation { purgeRecycleBinEntry(id: ${binned().single().id}) }""")
            .execute().path("purgeRecycleBinEntry").entity(Boolean::class.java).isEqualTo(true)

        assertThat(binned()).isEmpty()
        assertThat(workflows.findById(workflowId)).isEmpty
    }

    /** Emptying the bin and the nightly sweep are the same delete as a purge. */
    @Test
    fun `emptying the bin deletes the workflows in it`() {
        val (assignmentId, workflowId) = workflow("Short lived")
        del("removeWorkflow", assignmentId)

        graphQlTester.document("""mutation { emptyRecycleBin(workspaceId: $workspaceId) }""")
            .execute().path("emptyRecycleBin").entity(Int::class.java).isEqualTo(1)

        assertThat(workflows.findById(workflowId)).isEmpty
    }

    /** A workflow whose name was taken while it was in the bin is refused, and stays there. */
    @Test
    fun `restoring a workflow onto a taken name is refused`() {
        val (assignmentId, _) = workflow("Daily digest")
        del("removeWorkflow", assignmentId)
        workflow("Daily digest")

        graphQlTester.document("""mutation { restoreRecycleBinEntry(id: ${binned().single().id}) }""")
            .execute().errors().satisfy { errors ->
                assertThat(errors).singleElement().satisfies({ assertThat(it.message).contains("already exists") })
            }
        assertThat(binned()).hasSize(1)
    }

    /** An entry that is not there is said so, in words a client can translate. */
    @Test
    fun `restoring an entry that is not there is refused by name`() {
        graphQlTester.document("""mutation { restoreRecycleBinEntry(id: 999999999) }""")
            .execute().errors().satisfy { errors ->
                assertThat(errors).singleElement().satisfies({
                    assertThat(it.message).contains("No recycle bin entry")
                    assertThat(it.extensions["code"]).isEqualTo("RecycleBinEntryNotFound")
                })
            }
    }

    // -------------------------------------------------------- refusing, purging

    /** A name taken since the delete is refused, not silently worked around. */
    @Test
    fun `restoring onto a taken name is refused`() {
        val id = agent("Lead")
        deleteAgent(id)
        agent("Lead") // the name is taken again

        graphQlTester.document("""mutation { restoreRecycleBinEntry(id: ${binned().single().id}) }""")
            .execute().errors().satisfy { errors ->
                assertThat(errors).singleElement().satisfies({ assertThat(it.message).contains("already exists") })
            }
        // Still in the bin: a refused restore changes nothing.
        assertThat(binned()).hasSize(1)
    }

    /** Deleting an object forever takes it out of the bin without re-creating it. */
    @Test
    fun `purging removes an entry without restoring it`() {
        val id = tool("forecast")
        deleteTool(id)
        val entryId = binned().single().id!!

        graphQlTester.document("""mutation { purgeRecycleBinEntry(id: $entryId) }""")
            .execute().path("purgeRecycleBinEntry").entity(Boolean::class.java).isEqualTo(true)

        assertThat(binned()).isEmpty()
        assertThat(tools.findByWorkspaceIdAndName(workspaceId, "forecast")).isNull()
    }

    /** Emptying the bin removes everything in it and says how much. */
    @Test
    fun `emptying the bin clears it`() {
        deleteTool(tool("one"))
        deleteTool(tool("two"))
        assertThat(binned()).hasSize(2)

        graphQlTester.document("""mutation { emptyRecycleBin(workspaceId: $workspaceId) }""")
            .execute().path("emptyRecycleBin").entity(Int::class.java).isEqualTo(2)
        assertThat(binned()).isEmpty()
    }

    /** The bin lists only its own workspace's entries, newest first. */
    @Test
    fun `the bin lists this workspace's deletions`() {
        deleteTool(tool("forecast"))
        graphQlTester.document(
            """query { recycleBin(workspaceId: $workspaceId) { totalElements content { kind name } } }""",
        ).execute()
            .path("recycleBin.totalElements").entity(Int::class.java).isEqualTo(1)
            .path("recycleBin.content[0].name").entity(String::class.java).isEqualTo("forecast")
            .path("recycleBin.content[0].kind").entity(String::class.java).isEqualTo("TOOL")
    }

    /** The audit log says a restore happened, in the words the screen shows. */
    @Test
    fun `a restore is written into the audit log`() {
        val id = agent("Researcher")
        deleteAgent(id)
        restore(binned().single().id!!)

        assertThat(audit.findAll().filter { it.workspaceId == workspaceId }.map { it.message })
            .anySatisfy { assertThat(it).isEqualTo("Agent Researcher restored from the recycle bin") }
    }

    // ---------------------------------------------------------------- helpers

    private fun agent(name: String, prompt: String? = null): Long {
        val input = buildString {
            append("""workspaceId: $workspaceId, name: "$name", type: LLM""")
            if (prompt != null) append(""", systemPrompt: "$prompt"""")
        }
        return graphQlTester.document("""mutation { createAgent(input: { $input }) { id } }""")
            .execute().path("createAgent.id").entity(Long::class.java).get()
    }

    private fun tool(name: String): Long = graphQlTester.document(
        """mutation { createTool(input: { workspaceId: $workspaceId, name: "$name" }) { id } }""",
    ).execute().path("createTool.id").entity(Long::class.java).get()

    private fun function(name: String): Long = graphQlTester.document(
        """mutation { createFunction(input: { workspaceId: $workspaceId, name: "$name" }) { id } }""",
    ).execute().path("createFunction.id").entity(Long::class.java).get()

    private fun booleanFunction(name: String): Long = graphQlTester.document(
        """mutation { createFunction(input: { workspaceId: $workspaceId, name: "$name", returnType: BOOLEAN }) { id } }""",
    ).execute().path("createFunction.id").entity(Long::class.java).get()

    /** A shared condition; [asked] is the rest of the input, as GraphQL writes it. */
    private fun condition(asked: String): Long = graphQlTester.document(
        """mutation { createCondition(input: { workspaceId: $workspaceId, $asked }) { id } }""",
    ).execute().path("createCondition.id").entity(Long::class.java).get()

    private fun trigger(asked: String): Long = graphQlTester.document(
        """mutation { createTrigger(input: { workspaceId: $workspaceId, $asked }) { id } }""",
    ).execute().path("createTrigger.id").entity(Long::class.java).get()

    /** An object; [properties] is the list as GraphQL writes it. */
    private fun shape(name: String, properties: String, description: String? = null): Long = graphQlTester.document(
        """mutation { createObject(input: { workspaceId: $workspaceId, name: "$name", ${description?.let { """description: "$it", """ }.orEmpty()}properties: $properties }) { id } }""",
    ).execute().path("createObject.id").entity(Long::class.java).get()

    private fun variableCatalog(name: String): Long = graphQlTester.document(
        """mutation { createVariableCatalog(workspaceId: $workspaceId, name: "$name") { id } }""",
    ).execute().path("createVariableCatalog.id").entity(Long::class.java).get()

    private fun variable(catalogId: Long, asked: String): Long = graphQlTester.document(
        """mutation { createVariable(input: { workspaceId: $workspaceId, catalogId: $catalogId, $asked }) { id } }""",
    ).execute().path("createVariable.id").entity(Long::class.java).get()

    private fun memoryCatalog(name: String): Long = graphQlTester.document(
        """mutation { createMemoryCatalog(workspaceId: $workspaceId, name: "$name") { id } }""",
    ).execute().path("createMemoryCatalog.id").entity(Long::class.java).get()

    private fun memory(catalogId: Long, title: String, content: String): Long = graphQlTester.document(
        """mutation { createMemory(input: { catalogId: $catalogId, title: "$title", content: "$content" }) { id } }""",
    ).execute().path("createMemory.id").entity(Long::class.java).get()

    private fun issue(title: String): Long = graphQlTester.document(
        """mutation { createIssue(input: { workspaceId: $workspaceId, title: "$title" }) { id } }""",
    ).execute().path("createIssue.id").entity(Long::class.java).get()

    /** The assignment's id, which is what the workspace removes, and the definition's. */
    private fun workflow(name: String): Pair<Long, Long> {
        val made = graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "$name" }) { id workflowId } }""",
        ).execute()
        return made.path("createWorkflow.id").entity(Long::class.java).get() to
            made.path("createWorkflow.workflowId").entity(Long::class.java).get()
    }

    private fun deleteAgent(id: Long) = del("deleteAgent", id)
    private fun deleteTool(id: Long) = del("deleteTool", id)
    private fun deleteFunction(id: Long) = del("deleteFunction", id)
    private fun deleteSkill(id: Long) = del("deleteSkill", id)
    private fun deleteDocument(id: Long) = del("deleteDocument", id)
    private fun deleteDiagram(id: Long) = del("deleteDiagram", id)

    private fun del(mutation: String, id: Long) {
        graphQlTester.document("""mutation { $mutation(id: $id) }""")
            .execute().path(mutation).entity(Boolean::class.java).isEqualTo(true)
    }

    private fun restore(entryId: Long) {
        graphQlTester.document("""mutation { restoreRecycleBinEntry(id: $entryId) }""")
            .execute().path("restoreRecycleBinEntry").entity(Boolean::class.java).isEqualTo(true)
    }

    private companion object {
        val counter = AtomicLong(System.nanoTime())
    }
}
