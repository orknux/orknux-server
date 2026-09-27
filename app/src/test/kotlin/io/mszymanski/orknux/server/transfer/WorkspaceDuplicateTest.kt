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
) {

    /**
     * Skills keep their commands. Issue #570, again: the importer never set a
     * skill's key, so the second skill in the copy broke the unique index, and
     * the failed insert left the shared session unusable for everything after.
     */
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
                "connectionId", "name", "type", "url", "urlOverride", "pluginType", "authType", "smtpPort",
                "smtpUsername", "smtpFrom", "smtpSecurity", "headers",
            ),
            io.mszymanski.orknux.connector.connection.McpServer::class.java to setOf(
                "name", "address", "authType", "headers",
            ),
            io.mszymanski.orknux.connector.model.ModelProvider::class.java to setOf(
                "name", "type", "endpoint", "authMethod", "apiVersion", "deploymentName", "region", "tenantId",
                "clientId", "scope", "checkEnabled", "throttleTokensPerSecond", "throttleRequestsPerSecond",
                "acceptRetryAfter",
            ),
            io.mszymanski.orknux.connector.model.LlmModel::class.java to setOf(
                "name", "modelId", "kind", "contextWindow", "maxOutput", "parallelToolCalls", "temperature", "topP",
                "topK", "minP", "repeatPenalty", "enabled", "tokenLimit", "resetInterval", "requestsPerMinute",
                "throttleTokensPerSecond", "throttleRequestsPerSecond", "acceptRetryAfter", "inputCostPerMillion",
                "outputCostPerMillion", "voice", "skipEmptyLines", "imageCostPerImage",
            ),
        )
        copied.forEach { (type, carried) ->
            assertThat(fields(type) - carried - left)
                .describedAs("fields of ${type.simpleName} WorkspaceDuplicator has not decided on")
                .isEmpty()
        }
    }
}
