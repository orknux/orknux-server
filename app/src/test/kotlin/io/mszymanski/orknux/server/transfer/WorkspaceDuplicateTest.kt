package io.mszymanski.orknux.server.transfer

import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * Duplicating a workspace. Issue #570.
 *
 * One component that could not be copied - an agent on a model provider the
 * copy does not have - was caught and logged, but had already marked the
 * duplicate's transaction rollback-only, so the whole copy was thrown away and
 * the screen said INTERNAL_ERROR. What is pinned: the copy is made, what could
 * come came, and what could not is named in the answer.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class WorkspaceDuplicateTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val functions: io.mszymanski.orknux.server.action.WorkflowFunctionRepository,
    @Autowired val providers: io.mszymanski.orknux.connector.model.ModelProviderRepository,
    @Autowired val models: io.mszymanski.orknux.connector.model.LlmModelRepository,
    @Autowired val skillCatalogs: io.mszymanski.orknux.server.agent.SkillCatalogRepository,
    @Autowired val skills: io.mszymanski.orknux.server.agent.AgentSkillRepository,
    @Autowired val duplicator: WorkspaceDuplicator,
    @Autowired val transactions: org.springframework.transaction.PlatformTransactionManager,
    @Autowired val workspaceWorkflows: io.mszymanski.orknux.server.workflow.WorkspaceWorkflowRepository,
    @Autowired val variableCatalogs: io.mszymanski.orknux.server.variable.VariableCatalogRepository,
    @Autowired val variables: io.mszymanski.orknux.server.variable.WorkspaceVariableRepository,
    @Autowired val memoryCatalogs: io.mszymanski.orknux.server.memory.MemoryCatalogRepository,
    @Autowired val memories: io.mszymanski.orknux.server.memory.MemoryRepository,
    @Autowired val actions: io.mszymanski.orknux.server.action.WorkflowActionRepository,
) {

    /**
     * A copy says how far it has got as it goes. Issue #572: a large copy showed
     * nothing until it ended. Told after each component, with the kind under way
     * and how many of how many, ending at everything carried - and nothing is
     * left behind under the key once the copy has answered.
     */
    @Test
    fun `a copy reports its progress kind by kind, and forgets the key when it is done`() {
        val stamp = System.nanoTime()
        val source = requireNotNull(workspaces.save(Workspace(name = "dup-progress-$stamp")).id)
        val folder = requireNotNull(skillCatalogs.save(
            io.mszymanski.orknux.server.agent.SkillCatalog(workspaceId = source, name = "Playbooks $stamp"),
        ).id)
        listOf("One", "Two", "Three").forEach { name ->
            skills.save(
                io.mszymanski.orknux.server.agent.AgentSkill(
                    workspaceId = source, catalogId = folder, name = name, key = name.lowercase(),
                    content = "---\nname: $name\ndescription: How.\n---\n\n# $name\n\nDo it.\n",
                ),
            )
        }

        val steps = mutableListOf<WorkspaceCopyProgress.Step>()
        duplicator.duplicate(source, "dup-progress-copy-$stamp", "alice") { steps += it }

        assertThat(steps).isNotEmpty()
        assertThat(steps.first().overallDone).isZero()
        val last = steps.last()
        assertThat(last.overallDone).isEqualTo(last.overallTotal)
        val skillSteps = steps.filter { it.kind == "skill" }
        assertThat(skillSteps.map { it.done }).containsSubsequence(1, 2, 3)
        assertThat(skillSteps.all { it.total == 3 }).isTrue()

        // Through the API: a key reads null when nothing runs under it, before and after.
        graphQlTester.document("""{ workspaceCopyProgress(key: "dup-$stamp") { done } }""").execute()
            .path("workspaceCopyProgress").valueIsNull()
        graphQlTester.document(
            """mutation { duplicateWorkspace(id: $source, name: "dup-progress-api-$stamp", progressKey: "dup-$stamp") { problems } }""",
        ).execute().errors().verify()
        graphQlTester.document("""{ workspaceCopyProgress(key: "dup-$stamp") { done } }""").execute()
            .path("workspaceCopyProgress").valueIsNull()
    }

    /**
     * Somebody else gets a transaction while a copy runs. Issue #572, on SQLite:
     * the copy commits a component and begins the next at once, and SQLite's
     * busy wait - sleep, look again - never looked in the gap, so the page
     * asking how far the copy had got could not even load its session until
     * the copy was over, and the bar never moved. On Postgres nothing queues.
     *
     * A transaction on another thread, asked for while the copy is under way,
     * has to be answered within a few components of asking.
     */
    @Test
    fun `a transaction asked for during a copy is answered during it`() {
        val stamp = System.nanoTime()
        val source = requireNotNull(workspaces.save(Workspace(name = "dup-fair-$stamp")).id)
        val folder = requireNotNull(skillCatalogs.save(
            io.mszymanski.orknux.server.agent.SkillCatalog(workspaceId = source, name = "Playbooks $stamp"),
        ).id)
        val many = 150
        skills.saveAll((1..many).map { at ->
            val name = "Skill ${at.toString().map { 'a' + (it - '0') }.joinToString("")}"
            io.mszymanski.orknux.server.agent.AgentSkill(
                workspaceId = source, catalogId = folder, name = name,
                key = io.mszymanski.orknux.server.agent.SkillKeys.derive(name),
                content = "---\nname: $name\ndescription: How.\n---\n\n# $name\n\nDo it.\n",
            )
        })

        /*
         * Asked five times, at even points through the copy, one question at a
         * time: a single answer could be luck, since SQLite does look now and
         * again and the gap between two components is not always missed.
         */
        val carried = java.util.concurrent.atomic.AtomicInteger()
        val askAt = (1..5).map { it * many / 7 }
        val answers = java.util.concurrent.ConcurrentHashMap<Int, Int>()
        val observer = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            duplicator.duplicate(source, "dup-fair-copy-$stamp", "alice") { step ->
                carried.set(step.overallDone)
                if (step.overallDone in askAt) {
                    observer.execute {
                        runCatching {
                            org.springframework.transaction.support.TransactionTemplate(transactions)
                                .execute { workspaces.count() }
                        }
                        answers[step.overallDone] = carried.get()
                    }
                }
            }
            observer.shutdown()
            observer.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)
            // Within a few components of asking, where a fair queue puts it;
            // starved, it is answered only once the copy is over.
            val late = askAt.filter { asked -> (answers[asked] ?: many) >= asked + 10 }
                .map { it to answers[it] }
            assertThat(late)
                .describedAs("asked at, and carried when answered, of $many; all were ${askAt.map { it to answers[it] }}")
                .isEmpty()
        } finally {
            observer.shutdownNow()
        }
    }

    /**
     * Skills keep their commands. Issue #570, again: the importer never set a
     * skill's key, so the second skill in the copy broke the unique index, and
     * the failed insert left the shared session unusable for everything after.
     */
    /**
     * A copy named by nobody takes the first free name. Reported: the page always
     * asked for "<name> copy", so a second copy was refused as taken.
     */
    @Test
    fun `an unnamed copy takes the next free copy name, counting on from the original`() {
        val stamp = System.nanoTime()
        val source = requireNotNull(workspaces.save(Workspace(name = "dup-name-$stamp")).id)
        fun copyOf(id: Long): Pair<Long, String> {
            val made = graphQlTester.document(
                """mutation { duplicateWorkspace(id: $id) { workspace { id name } } }""",
            ).execute()
            return made.path("duplicateWorkspace.workspace.id").entity(Long::class.java).get() to
                made.path("duplicateWorkspace.workspace.name").entity(String::class.java).get()
        }

        val (firstId, first) = copyOf(source)
        assertThat(first).isEqualTo("dup-name-$stamp copy")
        assertThat(copyOf(source).second).isEqualTo("dup-name-$stamp copy 2")
        // A copy of a copy counts on from the original, not "copy copy".
        assertThat(copyOf(firstId).second).isEqualTo("dup-name-$stamp copy 3")
    }

    /**
     * Variables and memories come too, and before the components that name
     * them. A value comes with its variable, and a secret with its value too.
     * They were only ever named, after the components, so every function taking
     * a variable failed to copy and everything built on it after.
     */
    @Test
    fun `variables and memories are copied, a secret with its value`() {
        val stamp = System.nanoTime()
        val source = requireNotNull(workspaces.save(Workspace(name = "dup-vars-$stamp")).id)
        val keys = requireNotNull(variableCatalogs.save(io.mszymanski.orknux.server.variable.VariableCatalog(workspaceId = source, name = "Keys")).id)
        variables.save(
            io.mszymanski.orknux.server.variable.WorkspaceVariable(
                workspaceId = source, catalogId = keys, name = "region",
                kind = io.mszymanski.orknux.server.variable.VariableKind.VALUE, value = "westeurope",
            ),
        )
        variables.save(
            io.mszymanski.orknux.server.variable.WorkspaceVariable(
                workspaceId = source, catalogId = keys, name = "token",
                kind = io.mszymanski.orknux.server.variable.VariableKind.SECRET, value = "s3cret",
            ),
        )
        val notes = requireNotNull(
            memoryCatalogs.save(io.mszymanski.orknux.server.memory.MemoryCatalog(workspaceId = source, name = "General", isDefault = true)).id,
        )
        memories.save(io.mszymanski.orknux.server.memory.Memory(catalogId = notes, title = "Escalation", content = "Page the on-call."))

        val copy = graphQlTester.document(
            """mutation { duplicateWorkspace(id: $source, name: "dup-vars-copy-$stamp") { workspace { id } variablesToSet carried { kind count } } }""",
        ).execute()
        copy.errors().verify()
        val into = copy.path("duplicateWorkspace.workspace.id").entity(Long::class.java).get()

        val copied = variables.findByWorkspaceId(into).associateBy { it.name }
        assertThat(copied.getValue("region").value).isEqualTo("westeurope")
        assertThat(copied.getValue("token").value).isEqualTo("s3cret")
        copy.path("duplicateWorkspace.variablesToSet").entityList(String::class.java).hasSize(0)
        val catalog = memoryCatalogs.findByWorkspaceIdAndName(into, "General")
        assertThat(catalog?.isDefault).isTrue()
        assertThat(memories.findByCatalogIdAndTitle(requireNotNull(catalog?.id), "Escalation")?.content).isEqualTo("Page the on-call.")
    }

    /** What is switched off stays behind, and the workspace's own memory catalog comes whatever is left out. */
    @Test
    fun `a copy carries only the parts asked for`() {
        val stamp = System.nanoTime()
        val source = requireNotNull(workspaces.save(Workspace(name = "dup-parts-$stamp")).id)
        val keys = requireNotNull(variableCatalogs.save(io.mszymanski.orknux.server.variable.VariableCatalog(workspaceId = source, name = "Keys")).id)
        variables.save(
            io.mszymanski.orknux.server.variable.WorkspaceVariable(
                workspaceId = source, catalogId = keys, name = "region",
                kind = io.mszymanski.orknux.server.variable.VariableKind.VALUE, value = "westeurope",
            ),
        )
        val notes = requireNotNull(
            memoryCatalogs.save(io.mszymanski.orknux.server.memory.MemoryCatalog(workspaceId = source, name = "General", isDefault = true)).id,
        )
        memories.save(io.mszymanski.orknux.server.memory.Memory(catalogId = notes, title = "Escalation", content = "Page the on-call."))
        agents.save(io.mszymanski.orknux.server.agent.Agent(workspaceId = source, name = "Helper", type = io.mszymanski.orknux.server.agent.AgentType.LLM))

        val copy = graphQlTester.document(
            """mutation { duplicateWorkspace(id: $source, name: "dup-parts-copy-$stamp", parts: [AGENTS]) { workspace { id } } }""",
        ).execute()
        copy.errors().verify()
        val into = copy.path("duplicateWorkspace.workspace.id").entity(Long::class.java).get()

        assertThat(agents.findByWorkspaceId(into, org.springframework.data.domain.Pageable.unpaged()).content.map { it.name }).containsExactly("Helper")
        assertThat(variables.findByWorkspaceId(into)).isEmpty()
        val catalog = memoryCatalogs.findByWorkspaceIdAndName(into, "General")
        assertThat(catalog?.isDefault).isTrue()
        assertThat(memories.countByCatalogId(requireNotNull(catalog?.id))).isZero()
    }

    /** A speech action says something, and the copy says the same: it arrived with nothing and broke the action's CHECK. */
    @Test
    fun `a speech action is copied with what it says`() {
        val stamp = System.nanoTime()
        val source = requireNotNull(workspaces.save(Workspace(name = "dup-speak-$stamp")).id)
        graphQlTester.document(
            """mutation { createAction(input: { workspaceId: $source, name: "Announce", type: EXECUTE, subtype: SPEAK,
                 speechText: "The build is green", speechVoice: "alloy" }) { id } }""",
        ).execute().errors().verify()

        val copy = graphQlTester.document(
            """mutation { duplicateWorkspace(id: $source, name: "dup-speak-copy-$stamp") { workspace { id } problems } }""",
        ).execute()
        copy.path("duplicateWorkspace.problems").entityList(String::class.java).hasSize(0)
        val into = copy.path("duplicateWorkspace.workspace.id").entity(Long::class.java).get()
        val spoken = actions.findByWorkspaceId(into, org.springframework.data.domain.Pageable.unpaged()).content.single()
        assertThat(spoken.speechText).isEqualTo("The build is green")
        assertThat(spoken.speechVoice).isEqualTo("alloy")
    }

    /** A copied workflow runs like its original: published, in a workspace copy and as a duplicate alike; an empty one stays a draft. */
    @Test
    fun `a copied workflow arrives published`() {
        val stamp = System.nanoTime()
        val source = requireNotNull(workspaces.save(Workspace(name = "dup-pub-$stamp")).id)
        fun workflow(name: String) = graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $source, name: "$name" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()
        val drawn = workflow("dup-pub-drawn-$stamp")
        workflow("dup-pub-empty-$stamp")
        graphQlTester.document(
            """mutation { saveWorkflowGraph(workspaceId: $source, workflowId: $drawn, input: {
                 nodes: [{ key: "pic", kind: IMAGE, name: "Picture", x: 0, y: 0 }], edges: [] }) { workflowId } }""",
        ).execute().errors().verify()

        fun statuses(workspace: Long) = workspaceWorkflows.findByWorkspaceId(workspace, org.springframework.data.domain.Pageable.unpaged())
            .content.associate { it.workflow.name to it.workflow.status.name }

        val into = graphQlTester.document(
            """mutation { duplicateWorkspace(id: $source, name: "dup-pub-copy-$stamp") { workspace { id } } }""",
        ).execute().path("duplicateWorkspace.workspace.id").entity(Long::class.java).get()
        val copied = statuses(into)
        assertThat(copied.filterKeys { it.startsWith("dup-pub-drawn-$stamp") }.values).containsExactly("PUBLISHED")
        assertThat(copied.filterKeys { it.startsWith("dup-pub-empty-$stamp") }.values).containsExactly("DRAFT")

        graphQlTester.document("""mutation { duplicateComponent(workspaceId: $source, kind: WORKFLOW, id: $drawn) { name } }""")
            .execute().errors().verify()
        assertThat(statuses(source).filterKeys { it != "dup-pub-drawn-$stamp" && it.startsWith("dup-pub-drawn-$stamp") }.values)
            .containsExactly("PUBLISHED")
    }

    @Test
    fun `skills are copied with their commands, however many there are`() {
        val stamp = System.nanoTime()
        val source = requireNotNull(workspaces.save(Workspace(name = "dup-skills-$stamp")).id)
        val folder = requireNotNull(skillCatalogs.save(
            io.mszymanski.orknux.server.agent.SkillCatalog(workspaceId = source, name = "Playbooks $stamp"),
        ).id)
        listOf("When to escalate", "Answering in a thread").forEach { name ->
            val key = io.mszymanski.orknux.server.agent.SkillKeys.derive(name)
            skills.save(
                io.mszymanski.orknux.server.agent.AgentSkill(
                    workspaceId = source, catalogId = folder, name = name, key = key,
                    content = "---\nname: $name\ndescription: How.\n---\n\n# $name\n\nDo it well.\n",
                ),
            )
        }

        val copy = graphQlTester.document(
            """mutation { duplicateWorkspace(id: $source, name: "dup-skills-copy-$stamp") { workspace { id } problems } }""",
        ).execute()
        copy.errors().verify()
        val copiedId = copy.path("duplicateWorkspace.workspace.id").entity(Long::class.java).get()

        assertThat(copy.path("duplicateWorkspace.problems").entityList(String::class.java).get()).isEmpty()
        assertThat(skills.findByWorkspaceIdAndKeyIgnoreCase(copiedId, "when-to-escalate")).isNotNull()
        assertThat(skills.findByWorkspaceIdAndKeyIgnoreCase(copiedId, "answering-in-a-thread")).isNotNull()
    }

    /**
     * What components point at comes too. Issue #570: connections, model
     * providers and MCP servers were never copied, so an agent on a model and
     * every workflow built on a connection were left behind. They are copied
     * under the same names without their credentials, the agent comes with
     * them, the workspace's own model settings point at the copies, and the
     * answer says what needs a credential.
     */
    @Test
    fun `an agent comes with its model, and what needs a credential is named`() {
        val stamp = System.nanoTime()
        val source = requireNotNull(workspaces.save(Workspace(name = "dup-source-$stamp")).id)

        graphQlTester.document(
            """mutation { createFunction(input: { workspaceId: $source, name: "greet$stamp" }) { id } }""",
        ).execute().errors().verify()

        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $source, name: "Local $stamp", endpoint: "http://localhost:9/v1", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        val modelId = graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "Gemma $stamp", modelId: "gemma", kind: CHAT })
               { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
        agents.save(Agent(workspaceId = source, name = "Tester $stamp", type = AgentType.LLM, modelId = modelId))
        workspaces.findByIdOrNull(source)!!.let { it.quickChatModelId = modelId; workspaces.save(it) }

        val copy = graphQlTester.document(
            """mutation { duplicateWorkspace(id: $source, name: "dup-copy-$stamp") {
                 workspace { id } carried { kind count } credentialsToSet problems } }""",
        ).execute()
        copy.errors().verify()
        val copiedId = copy.path("duplicateWorkspace.workspace.id").entity(Long::class.java).get()

        assertThat(copy.path("duplicateWorkspace.problems").entityList(String::class.java).get()).isEmpty()
        assertThat(copy.path("duplicateWorkspace.credentialsToSet").entityList(String::class.java).get())
            .containsExactly("model provider Local $stamp")
        assertThat(functions.findByWorkspaceIdAndName(copiedId, "greet$stamp")).isNotNull()

        val copiedProvider = requireNotNull(providers.findByWorkspaceIdAndName(copiedId, "Local $stamp"))
        assertThat(copiedProvider.secret).isNull()
        val copiedModel = requireNotNull(models.findByProviderIdAndName(copiedProvider.id!!, "Gemma $stamp"))
        assertThat(agents.findByWorkspaceIdAndName(copiedId, "Tester $stamp")!!.modelId).isEqualTo(copiedModel.id)
        assertThat(workspaces.findByIdOrNull(copiedId)!!.quickChatModelId).isEqualTo(copiedModel.id)
    }

    /**
     * Every field of what is copied has been decided on. The copy is written
     * field by field; a field added to one of these entities and not listed
     * here - copied or deliberately left - fails, so it cannot be silently
     * dropped from every duplicate.
     */
    @Test
    fun `every field of a connection, provider, model and MCP server is either copied or left on purpose`() {
        fun fields(type: Class<*>) = type.declaredFields
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .map { it.name }.toSet()
        val left = setOf("id", "workspaceId", "providerId", "secret", "secretVariableId", "appToken",
            "appTokenVariableId", "userToken", "userTokenVariableId", "lastCheckStatus", "lastCheckMessage",
            "lastCheckedAt", "status", "reachable", "checkDetail", "toolCount")
        val copied = mapOf(
            io.mszymanski.orknux.connector.connection.WorkspaceConnection::class.java to setOf(
                "connectionId", "name", "type", "url", "description", "urlOverride", "grantNewAgents", "pluginType", "authType", "smtpPort",
                "smtpUsername", "smtpFrom", "smtpSecurity", "headers",
            ),
            io.mszymanski.orknux.connector.connection.McpServer::class.java to setOf(
                "name", "address", "authType", "headers",
            ),
            io.mszymanski.orknux.connector.model.ModelProvider::class.java to setOf(
                "name", "type", "endpoint", "authMethod", "apiVersion", "deploymentName", "region", "tenantId",
                "clientId", "scope", "checkEnabled", "throttleTokensPerSecond", "throttleRequestsPerSecond",
                "acceptRetryAfter", "chatApi", "sendsReasoning", "offerAllTools", "maxTools", "editMask",
            ),
            io.mszymanski.orknux.connector.model.LlmModel::class.java to setOf(
                "name", "modelId", "kind", "contextWindow", "maxOutput", "parallelToolCalls", "reasoningEffort", "temperature", "topP",
                "topK", "minP", "repeatPenalty", "enabled", "tokenLimit", "resetInterval", "requestsPerMinute",
                "throttleTokensPerSecond", "throttleRequestsPerSecond", "acceptRetryAfter", "answerLimitMinutes", "inputCostPerMillion",
                "outputCostPerMillion", "cachedInputCostPerMillion", "cacheWriteCostPerMillion", "voice", "skipEmptyLines", "imageCostPerImage",
            ),
        )
        copied.forEach { (type, carried) ->
            assertThat(fields(type) - carried - left)
                .describedAs("fields of ${type.simpleName} WorkspaceDuplicator has not decided on")
                .isEmpty()
        }
    }

    /**
     * And every setting of the workspace itself. Issue #590: the copy carried a
     * hand-kept list that had fallen behind the entity - the timeouts and the
     * voice settings stayed with the original - and a file has to carry the same
     * list. Both read [WORKSPACE_SETTINGS] now; a field added to [Workspace] and
     * not put there or here fails.
     */
    @Test
    fun `every field of a workspace is either a carried setting, a model choice, or left on purpose`() {
        val fields = Workspace::class.java.declaredFields
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .map { it.name }.toSet()
        // Its id and its name are the copy's own; who may see it is a decision about people.
        val left = setOf("id", "name", "roles", "adminRoles")
        // Carried by name and pointed at the copied models.
        val models = setOf("companionModelId", "transcriptionModelId", "speechModelId", "compactionModelId",
            "imageModelId", "quickChatModelId", "sessionCompactionModelId", "completionModelId")
        assertThat(WORKSPACE_MODEL_CHOICES).hasSize(models.size)
        assertThat(fields - WORKSPACE_SETTINGS.map { it.key }.toSet() - models - left)
            .describedAs("fields of Workspace a copy and a workspace file have not decided on")
            .isEmpty()
    }
}
