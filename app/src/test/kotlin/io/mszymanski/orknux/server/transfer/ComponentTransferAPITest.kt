package io.mszymanski.orknux.server.transfer

import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.agent.AgentSkillRepository
import io.mszymanski.orknux.server.agent.AgentToolRepository
import io.mszymanski.orknux.server.agent.SkillCatalogRepository
import io.mszymanski.orknux.server.condition.WorkflowConditionRepository
import io.mszymanski.orknux.server.obj.WorkflowObjectRepository
import io.mszymanski.orknux.server.variable.VariableCatalog
import io.mszymanski.orknux.server.variable.VariableCatalogRepository
import io.mszymanski.orknux.server.variable.VariableKind
import io.mszymanski.orknux.server.variable.VariableType
import io.mszymanski.orknux.server.variable.WorkspaceVariable
import io.mszymanski.orknux.server.variable.WorkspaceVariableRepository
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
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * Moving components between workspaces as JSON.
 *
 * What is worth holding here is not that a round trip works — it is the three
 * things that would otherwise be found out by somebody losing work: that no id
 * and no secret ever reaches the file, that a name already taken is renamed
 * rather than replaced and everything pointing at it follows, and that an
 * envelope from a future version is refused whole rather than read in part.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ComponentTransferAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val objects: WorkflowObjectRepository,
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val conditions: WorkflowConditionRepository,
    @Autowired val tools: AgentToolRepository,
    @Autowired val skills: AgentSkillRepository,
    @Autowired val catalogs: SkillCatalogRepository,
    @Autowired val variables: WorkspaceVariableRepository,
    @Autowired val variableCatalogs: VariableCatalogRepository,
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
        // An action points at its function, and a class run before this one in
        // the same JVM (StreamOutputTest, AgentNodeRunnerTest) leaves one behind:
        // its workflow first, then the action, or no function can be deleted.
        workflows.deleteAll()
        actions.deleteAll()
        conditions.deleteAll()
        functions.deleteAll()
        objects.deleteAll()
        tools.deleteAll()
        skills.deleteAll()
        catalogs.deleteAll()
        variables.deleteAll()
        variableCatalogs.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        from = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        into = requireNotNull(workspaces.save(Workspace(name = "frontend")).id)
    }

    @Test
    fun `a deep export carries what the function is typed against, and no ids`() {
        val objectId = createObject(from, "Order")
        val functionId = createFunction(from, "normalise", returnObjectId = objectId)

        val json = export(from, "FUNCTION", functionId, "DEEP")
        val envelope = mapper.readTree(json)

        assertThat(envelope.path("formatVersion").asInt()).isEqualTo(1)
        assertThat(envelope.path("producedBy").stringValue()).startsWith("Orknux ")
        assertThat(envelope.path("components").values().map { it.path("kind").stringValue() })
            .containsExactly("OBJECT", "FUNCTION")
        // The reference is a name. An id from the installation that wrote the
        // file would mean nothing in the one that reads it.
        assertThat(envelope.path("components").values().last().path("returnObjectRef").stringValue())
            .isEqualTo("Order")
        // Structurally, not by searching the text: an id is a number, and any
        // digits it happens to share with a timestamp would make a text search
        // pass or fail for the wrong reason. What matters is that no field in
        // the envelope is one.
        assertThat(fieldNames(envelope)).allSatisfy { field ->
            assertThat(field).isNotEqualTo("id").doesNotEndWith("Id")
        }
        assertThat(objectId).isNotNull()
    }

    /** Every field name anywhere in the tree, however deep. */
    private fun fieldNames(node: JsonNode): List<String> = when {
        node.isObject -> node.properties().flatMap { (name, value) -> listOf(name) + fieldNames(value) }
        node.isArray -> node.values().flatMap { fieldNames(it) }
        else -> emptyList()
    }

    @Test
    fun `a shallow export carries the one thing, and the import resolves the rest here`() {
        val objectId = createObject(from, "Order")
        val functionId = createFunction(from, "normalise", returnObjectId = objectId)

        val json = export(from, "FUNCTION", functionId, "SHALLOW")
        assertThat(mapper.readTree(json).path("components").size()).isEqualTo(1)

        // Nothing called Order in the target: the plan says so and refuses.
        val refused = plan(into, json)
        assertThat(refused.importable).isFalse()
        assertThat(refused.entries.single { it.disposition == "MISSING" }.name).isEqualTo("Order")

        // Give the target one of its own and the same file goes in, pointing at it.
        createObject(into, "Order")
        val allowed = plan(into, json)
        assertThat(allowed.importable).isTrue()
        assertThat(allowed.entries.single { it.disposition == "REUSE" }.name).isEqualTo("Order")

        import(into, json)
        val imported = functions.findByWorkspaceIdAndName(into, "normalise")!!
        assertThat(imported.returnObjectId).isEqualTo(objects.findByWorkspaceIdAndName(into, "Order")!!.id)
    }

    @Test
    fun `a name already taken is renamed, and everything in the same file follows it`() {
        val objectId = createObject(from, "Order")
        createFunction(from, "normalise", returnObjectId = objectId)

        // The target already holds an Order and a normalise of its own, which
        // are not ours to replace.
        val theirObject = createObject(into, "Order")
        val theirFunction = createFunction(into, "normalise")

        val json = export(from, "FUNCTION", functions.findByWorkspaceIdAndName(from, "normalise")!!.id!!, "DEEP")
        val told = plan(into, json)
        assertThat(told.entries.map { it.name to it.targetName })
            .containsExactly("Order" to "Order_2", "normalise" to "normalise_2")
        assertThat(told.entries.map { it.disposition }).containsOnly("RENAME")

        import(into, json)

        // Theirs is untouched, ours arrived beside it, and ours points at ours.
        assertThat(objects.findById(theirObject).get().name).isEqualTo("Order")
        assertThat(functions.findById(theirFunction).get().name).isEqualTo("normalise")
        val arrived = functions.findByWorkspaceIdAndName(into, "normalise_2")!!
        assertThat(arrived.returnObjectId).isEqualTo(objects.findByWorkspaceIdAndName(into, "Order_2")!!.id)
        assertThat(audit.findAll().map { it.message })
            .contains("Function normalise imported as normalise_2", "Object Order imported as Order_2")
    }

    @Test
    fun `a composite condition and the function it calls travel together`() {
        val functionId = createFunction(from, "isUrgent", returnType = "BOOLEAN")
        val leaf = createCondition(from, "By a function", type = "FUNCTION", functionId = functionId)
        val other = createCondition(from, "Out of hours")
        val compositeId = createCondition(from, "Either", type = "ANY_OF", members = listOf(leaf, other))

        val json = export(from, "CONDITION", compositeId, "DEEP")
        import(into, json)

        val here = conditions.findByWorkspaceIdAndName(into, "Either")!!
        assertThat(here.members).containsExactly(
            conditions.findByWorkspaceIdAndName(into, "By a function")!!.id,
            conditions.findByWorkspaceIdAndName(into, "Out of hours")!!.id,
        )
        assertThat(conditions.findByWorkspaceIdAndName(into, "By a function")!!.functionId)
            .isEqualTo(functions.findByWorkspaceIdAndName(into, "isUrgent")!!.id)
    }

    @Test
    fun `a variable is named and never carried, and the import refuses until the target has one`() {
        val catalog = variableCatalogs.save(VariableCatalog(workspaceId = from, name = "Keys")).id!!
        val secret = variables.save(
            WorkspaceVariable(
                workspaceId = from,
                catalogId = catalog,
                name = "webhookSecret",
                type = VariableType.STRING,
                kind = VariableKind.SECRET,
                value = "hunter2-do-not-export",
            ),
        ).id!!
        val functionId = createFunction(from, "verify", externalVariableIds = listOf(secret))

        val json = export(from, "FUNCTION", functionId, "DEEP")
        // The whole point: the name travels, the value does not.
        assertThat(json).contains("webhookSecret").doesNotContain("hunter2")

        val refused = plan(into, json)
        assertThat(refused.importable).isFalse()
        assertThat(refused.problems.single()).contains("no variable called webhookSecret")

        graphQlTester.document(
            """mutation { importComponents(workspaceId: $into, envelope: ${quote(json)}) { importable } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors.single().message).contains("Nothing was imported")
        }
        assertThat(functions.findByWorkspaceIdAndName(into, "verify")).isNull()

        // The target's own variable, with its own value, is what it points at.
        val theirCatalog = variableCatalogs.save(VariableCatalog(workspaceId = into, name = "Keys")).id!!
        val theirs = variables.save(
            WorkspaceVariable(
                workspaceId = into,
                catalogId = theirCatalog,
                name = "webhookSecret",
                type = VariableType.STRING,
                value = "their own",
            ),
        ).id!!

        import(into, json)
        assertThat(functions.findByWorkspaceIdAndName(into, "verify")!!.externals.map { it.variableId })
            .containsExactly(theirs)
    }

    @Test
    fun `a version from the future is refused by name rather than read in part`() {
        val toolId = createTool(from, "lookup")
        val json = export(from, "TOOL", toolId, "DEEP")
        val ahead = json.replace("\"formatVersion\" : 1", "\"formatVersion\" : 2")
        assertThat(ahead).contains("\"formatVersion\" : 2")

        graphQlTester.document(
            """query { componentImportPlan(workspaceId: $into, envelope: ${quote(ahead)}) { importable } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors.single().message)
                .contains("format version 2")
                .contains("reads version 1")
        }
        assertThat(tools.findByWorkspaceIdAndName(into, "lookup")).isNull()
    }

    @Test
    fun `a file that is not an export says so rather than failing obscurely`() {
        graphQlTester.document(
            """query { componentImportPlan(workspaceId: $into, envelope: "{\"hello\":true}") { importable } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors.single().message).contains("carries no formatVersion")
        }
    }

    @Test
    fun `a skill lands in a folder of the same name, and the tool round-trips whole`() {
        val toolId = createTool(from, "lookup")
        val skillId = createSkill(from, "Triage", catalog = "Support")

        import(into, export(from, "TOOL", toolId, "DEEP"))
        import(into, export(from, "SKILL", skillId, "DEEP"))

        val tool = tools.findByWorkspaceIdAndName(into, "lookup")!!
        assertThat(tool.source).isEqualTo(tools.findById(toolId).get().source)
        assertThat(tool.typescript).isEqualTo(tools.findById(toolId).get().typescript)

        val skill = skills.findByWorkspaceIdAndName(into, "Triage")!!
        assertThat(catalogs.findById(skill.catalogId).get().name).isEqualTo("Support")
    }

    @Test
    fun `a workspace that is not there is refused before the file is even read`() {
        val functionId = createFunction(from, "normalise")
        graphQlTester.document(
            """query { exportComponent(workspaceId: 999999, kind: FUNCTION, id: $functionId) { json } }""",
        ).execute().errors().satisfy { errors -> assertThat(errors).isNotEmpty() }

        graphQlTester.document(
            """mutation { importComponents(workspaceId: 999999, envelope: "{}") { importable } }""",
        ).execute().errors().satisfy { errors -> assertThat(errors).isNotEmpty() }
    }

    @Test
    fun `leaving out a component the workspace already has points what needed it at that one`() {
        val objectId = createObject(from, "Order")
        createFunction(from, "normalise", returnObjectId = objectId)
        val theirObject = createObject(into, "Order")

        val json = export(from, "FUNCTION", functions.findByWorkspaceIdAndName(from, "normalise")!!.id!!, "DEEP")

        // Both are carried, so both may be left out; nothing else on the plan is.
        assertThat(plan(into, json).entries.filter { it.carried }.map { it.name })
            .containsExactly("Order", "normalise")

        val without = plan(into, json, exclude = listOf("OBJECT" to "Order"))
        assertThat(without.importable).isTrue()
        assertThat(without.entries.single { it.name == "Order" && it.disposition == "EXCLUDE" }.carried).isTrue()
        // The reference is not dropped with it: it now has to be satisfied here,
        // and it is, by the Order the target already had.
        assertThat(without.entries.single { it.name == "Order" && it.disposition == "REUSE" }.carried).isFalse()

        import(into, json, exclude = listOf("OBJECT" to "Order"))

        assertThat(objects.findByWorkspaceId(into).map { it.name }).containsExactly("Order")
        assertThat(functions.findByWorkspaceIdAndName(into, "normalise")!!.returnObjectId).isEqualTo(theirObject)
    }

    @Test
    fun `leaving out one that nothing here can replace takes what needed it, and says which`() {
        val objectId = createObject(from, "Order")
        createFunction(from, "normalise", returnObjectId = objectId)
        val json = export(from, "FUNCTION", functions.findByWorkspaceIdAndName(from, "normalise")!!.id!!, "DEEP")

        // Nothing called Order in the target, so the function has nothing to be
        // typed against: it goes too, rather than being quietly created broken.
        val without = plan(into, json, exclude = listOf("OBJECT" to "Order"))
        assertThat(without.entries.filter { it.disposition == "EXCLUDE" }.map { it.name })
            .containsExactly("Order", "normalise")
        assertThat(without.entries.single { it.name == "normalise" }.detail)
            .contains("Left out too")
            .contains("an object called Order")

        // And with everything in the file left out there is nothing to do, which
        // the plan says rather than offering an import that creates nothing.
        assertThat(without.importable).isFalse()
        assertThat(without.problems.single()).contains("nothing left to import")

        graphQlTester.document(
            """
            mutation {
              importComponents(
                workspaceId: $into, envelope: ${quote(json)}, exclude: [{ kind: OBJECT, name: "Order" }]
              ) { importable }
            }
            """,
        ).execute().errors().satisfy { errors ->
            assertThat(errors.single().message).contains("Nothing was imported")
        }
        assertThat(functions.findByWorkspaceId(into)).isEmpty()
    }

    @Test
    fun `only what the file carries can be left out`() {
        val objectId = createObject(from, "Order")
        val functionId = createFunction(from, "normalise", returnObjectId = objectId)
        // Shallow: the envelope names Order and does not hold it, so there is
        // nothing there to leave out - the fix for that row is to make one here.
        val json = export(from, "FUNCTION", functionId, "SHALLOW")

        assertThat(plan(into, json).entries.single { it.name == "Order" }.carried).isFalse()

        graphQlTester.document(
            """
            query {
              componentImportPlan(
                workspaceId: $into, envelope: ${quote(json)}, exclude: [{ kind: OBJECT, name: "Order" }]
              ) { importable }
            }
            """,
        ).execute().errors().satisfy { errors ->
            assertThat(errors.single().message).contains("This file carries no object called Order")
        }
    }

    @Test
    fun `an id from another workspace is not a way to read its code`() {
        val theirs = createFunction(into, "secretSauce")
        graphQlTester.document(
            """query { exportComponent(workspaceId: $from, kind: FUNCTION, id: $theirs) { json } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors.single().message).isEqualTo("This workspace has no function with id $theirs")
        }
    }

    // ----------------------------------------------------------------- helpers

    private data class Entry(
        val kind: String?,
        val name: String,
        val targetName: String,
        val disposition: String,
        val carried: Boolean = false,
        val detail: String = "",
    )

    /**
     * A function that imports another travels with it, and points at it again.
     *
     * Two halves crossing an installation boundary. The reference has to be a
     * name, because the ids on this side mean nothing on the other; the local
     * name has to be carried as it stands, because it is a word in the source
     * that came in the same file. Getting either wrong imports a function whose
     * `imports` object is empty and whose code is full of calls into it.
     */
    @Test
    fun `an imported function is carried, and the import is wired up again`() {
        val shared = createFunction(from, "toUpper")
        val caller = createFunction(from, "shout", imports = listOf(shared to "upper"))

        val json = export(from, "FUNCTION", caller, "DEEP")
        val envelope = mapper.readTree(json)

        assertThat(envelope.path("components").values().map { it.path("kind").stringValue() })
            .containsExactly("FUNCTION", "FUNCTION")
        val written = envelope.path("components").values()
            .single { it.path("name").stringValue() == "shout" }
            .path("imports").values().single()
        assertThat(written.path("functionRef").stringValue()).isEqualTo("toUpper")
        assertThat(written.path("name").stringValue()).isEqualTo("upper")
        assertThat(json).doesNotContain("\"functionId\"")

        assertThat(import(into, json).importable).isTrue()

        val landed = functions.findByWorkspaceId(into).single { it.name == "shout" }
        val target = functions.findByWorkspaceId(into).single { it.name == "toUpper" }
        assertThat(landed.imports.single().importName).isEqualTo("upper")
        assertThat(landed.imports.single().importedId).isEqualTo(target.id)
    }

    /** Two deep exports sharing an object: one plan, one Order, both functions. */
    @Test
    fun `several files import as one, and what two of them share arrives once`() {
        val objectId = createObject(from, "Order")
        val first = export(from, "FUNCTION", createFunction(from, "normalise", returnObjectId = objectId), "DEEP")
        val second = export(from, "FUNCTION", createFunction(from, "price", returnObjectId = objectId), "DEEP")

        val plan = importMany(into, listOf(first, second))

        assertThat(plan.importable).isTrue()
        assertThat(plan.entries.map { it.kind to it.name })
            .containsExactlyInAnyOrder("OBJECT" to "Order", "FUNCTION" to "normalise", "FUNCTION" to "price")
        assertThat(objects.findByWorkspaceId(into).map { it.name }).containsExactly("Order")
        assertThat(functions.findByWorkspaceId(into).map { it.name }).containsExactlyInAnyOrder("normalise", "price")
    }

    /** The same name in two files, written differently: no guessing which was meant. */
    @Test
    fun `two files holding different copies of one component are refused, and nothing is written`() {
        val one = export(from, "OBJECT", createObject(from, "Order"), "DEEP")
        val other = mapper.readTree(one).also { root ->
            (root.path("components").get(0) as tools.jackson.databind.node.ObjectNode).put("description", "changed")
        }.toString()

        graphQlTester.document(
            """mutation { importComponents(workspaceId: $into, envelopes: [${quote(one)}, ${quote(other)}]) { importable } }""",
        ).execute().errors().expect { it.message.orEmpty().contains("Two of these files hold") }.verify()

        assertThat(objects.findByWorkspaceId(into)).isEmpty()
    }

    private fun importMany(workspaceId: Long, envelopes: List<String>): Plan = read(
        graphQlTester.document(
            """
            mutation {
              importComponents(workspaceId: $workspaceId, envelopes: ${envelopes.joinToString(", ", "[", "]") { quote(it) }}) {
                importable problems entries { kind name targetName disposition carried detail }
              }
            }
            """,
        ).execute().path("importComponents").entity(Map::class.java).get().let(mapper::valueToTree),
    )

    private data class Plan(val importable: Boolean, val entries: List<Entry>, val problems: List<String>)

    private fun export(workspaceId: Long, kind: String, id: Long, depth: String): String = graphQlTester.document(
        """query { exportComponent(workspaceId: $workspaceId, kind: $kind, id: $id, depth: $depth) { json } }""",
    ).execute().path("exportComponent.json").entity(String::class.java).get()

    private fun plan(
        workspaceId: Long,
        envelope: String,
        exclude: List<Pair<String, String>> = emptyList(),
    ): Plan = read(
        graphQlTester.document(
            """
            query {
              componentImportPlan(
                workspaceId: $workspaceId, envelope: ${quote(envelope)}, exclude: ${excluding(exclude)}
              ) {
                importable problems entries { kind name targetName disposition carried detail }
              }
            }
            """,
        ).execute().path("componentImportPlan").entity(Map::class.java).get().let(mapper::valueToTree),
    )

    private fun import(
        workspaceId: Long,
        envelope: String,
        exclude: List<Pair<String, String>> = emptyList(),
    ): Plan = read(
        graphQlTester.document(
            """
            mutation {
              importComponents(
                workspaceId: $workspaceId, envelope: ${quote(envelope)}, exclude: ${excluding(exclude)}
              ) {
                importable problems entries { kind name targetName disposition carried detail }
              }
            }
            """,
        ).execute().path("importComponents").entity(Map::class.java).get().let(mapper::valueToTree),
    )

    /** `[{ kind: OBJECT, name: "Order" }]`, as the argument wants it. */
    private fun excluding(exclude: List<Pair<String, String>>): String =
        exclude.joinToString(", ", "[", "]") { (kind, name) -> "{ kind: $kind, name: ${quote(name)} }" }

    private fun read(node: JsonNode): Plan = Plan(
        importable = node.path("importable").asBoolean(false),
        entries = node.path("entries").values().map {
            Entry(
                kind = it.path("kind").takeIf { held -> held.isString }?.stringValue(),
                name = it.path("name").stringValue(),
                targetName = it.path("targetName").stringValue(),
                disposition = it.path("disposition").stringValue(),
                carried = it.path("carried").asBoolean(false),
                detail = it.path("detail").asString(""),
            )
        },
        problems = node.path("problems").values().map { it.stringValue() },
    )

    /** The envelope as a GraphQL string literal, quotes and newlines and all. */
    private fun quote(json: String): String = mapper.writeValueAsString(json)

    private fun createObject(workspaceId: Long, name: String): Long = graphQlTester.document(
        """mutation { createObject(input: { workspaceId: $workspaceId, name: "$name",
             properties: [{ name: "reference", kind: STRING }] }) { id } }""",
    ).execute().path("createObject.id").entity(Long::class.java).get()

    private fun createFunction(
        workspaceId: Long,
        name: String,
        returnType: String = "MAP",
        returnObjectId: Long? = null,
        externalVariableIds: List<Long> = emptyList(),
        imports: List<Pair<Long, String>> = emptyList(),
    ): Long {
        val arity = externalVariableIds.size
        val args = (0 until arity).joinToString(", ") { "a$it" }
        val body = "export default function ($args) { return {}; }"
        val imported = imports.joinToString(", ", "[", "]") { (id, called) ->
            """{ functionId: $id, name: "$called" }"""
        }
        return graphQlTester.document(
            """
            mutation {
              createFunction(input: {
                workspaceId: $workspaceId, name: "$name",
                source: ${quote(body)}, typescript: ${quote(body)},
                returnType: ${if (returnObjectId != null) "OBJECT" else returnType}
                ${returnObjectId?.let { ", returnObjectId: $it" } ?: ""}
                ${if (arity > 0) ", externalVariableIds: [${externalVariableIds.joinToString(", ")}]" else ""}
                ${if (imports.isNotEmpty()) ", imports: $imported" else ""}
              }) { id }
            }
            """,
        ).execute().path("createFunction.id").entity(Long::class.java).get()
    }

    private fun createCondition(
        workspaceId: Long,
        name: String,
        type: String = "SLACK",
        functionId: Long? = null,
        members: List<Long> = emptyList(),
    ): Long {
        val extra = when {
            functionId != null -> ", functionId: $functionId"
            members.isNotEmpty() -> ", members: [${members.joinToString(", ")}]"
            else -> ", property: MESSAGE_TEXT, check: CONTAINS, values: [${quote("urgent")}]"
        }
        return graphQlTester.document(
            """
            mutation {
              createCondition(input: {
                workspaceId: $workspaceId, name: "$name", type: $type$extra
              }) { id }
            }
            """,
        ).execute().path("createCondition.id").entity(Long::class.java).get()
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
}
