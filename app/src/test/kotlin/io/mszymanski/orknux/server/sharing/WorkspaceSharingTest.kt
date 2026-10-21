package io.mszymanski.orknux.server.sharing

import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.variable.VariableTools
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

/**
 * One workspace lending another a variable catalog and a model provider: the
 * borrower's agents use them, by a grant the owner's renames cannot break,
 * and taking the share back takes the use back with it.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class WorkspaceSharingTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val variableTools: VariableTools,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val secretVariables: io.mszymanski.orknux.server.variable.AppSecretVariables,
) {

    private var owner: Long = 0
    private var borrower: Long = 0
    private var catalog: Long = 0
    private var provider: Long = 0
    private var model: Long = 0

    @BeforeEach
    fun make() {
        owner = requireNotNull(workspaces.save(Workspace(name = "platform-${System.nanoTime()}")).id)
        borrower = requireNotNull(workspaces.save(Workspace(name = "team-${System.nanoTime()}")).id)
        catalog = id("""mutation { createVariableCatalog(workspaceId: $owner, name: "Shared keys") { id } }""", "createVariableCatalog.id")
        graphQlTester.document(
            """mutation { createVariable(input: { workspaceId: $owner, catalogId: $catalog, name: "region",
                 type: STRING, kind: VALUE, value: "westeurope" }) { id } }""",
        ).execute().errors().verify()
        provider = id(
            """mutation { createModelProvider(input: { workspaceId: $owner, name: "Azure", endpoint: "http://models.invalid",
                 secret: "sk-never-shown" }) { id } }""",
            "createModelProvider.id",
        )
        model = id(
            """mutation { createModel(input: { providerId: $provider, name: "GPT", modelId: "gpt", kind: CHAT }) { id } }""",
            "createModel.id",
        )
    }

    private fun id(document: String, path: String): Long =
        graphQlTester.document(document).execute().errors().verify().path(path).entity(Long::class.java).get()

    private fun share(kind: String, component: Long, to: Long = borrower) = graphQlTester.document(
        """mutation { shareWithWorkspace(input: { workspaceId: $owner, granteeWorkspaceId: $to, kind: $kind, componentId: $component })
             { id grant componentName ownerWorkspaceName } }""",
    ).execute()

    private fun agent(): Agent = agents.save(Agent(workspaceId = borrower, name = "Helper ${System.nanoTime()}", type = AgentType.LLM))

    @Test
    fun `a borrower's agent reads a lent catalog and answers on a lent provider's model`() {
        val catalogShare = share("VARIABLE_CATALOG", catalog)
        catalogShare.path("shareWithWorkspace.grant").entity(String::class.java).isEqualTo("share:$catalog")
        catalogShare.path("shareWithWorkspace.componentName").entity(String::class.java).isEqualTo("Shared keys")
        share("MODEL_PROVIDER", provider).errors().verify()

        graphQlTester.document("query { workspaceSharesReceived(workspaceId: $borrower) { kind grant } }").execute()
            .path("workspaceSharesReceived[*].kind").entityList(String::class.java).containsExactly("VARIABLE_CATALOG", "MODEL_PROVIDER")
        graphQlTester.document("query { sharedModels(workspaceId: $borrower) { id name } }").execute()
            .path("sharedModels[*].name").entityList(String::class.java).containsExactly("GPT")
        // Lent to the borrower, not to the owner's own list.
        graphQlTester.document("query { workspaceSharesGiven(workspaceId: $owner) { granteeWorkspaceId } }").execute()
            .path("workspaceSharesGiven[*].granteeWorkspaceId").entityList(Long::class.java).containsExactly(borrower, borrower)

        val helper = agent()
        graphQlTester.document(
            """mutation { updateAgent(id: ${helper.id}, input: { name: "${helper.name}", modelId: $model,
                 variableCatalogs: ["share:$catalog"] }) { modelId variableCatalogs } }""",
        ).execute().errors().verify()
            .path("updateAgent.modelId").entity(Long::class.java).isEqualTo(model)

        val held = requireNotNull(agents.findById(requireNotNull(helper.id)).orElse(null))
        assertThat(variableTools.run(held, VariableTools.GET, """{"name":"region"}""")).contains("westeurope")
    }

    @Test
    fun `a lent variable is offered to the borrower's pickers and usable by its components, until the share is taken back`() {
        val token = id(
            """mutation { createVariable(input: { workspaceId: $owner, catalogId: $catalog, name: "token",
                 type: STRING, kind: SECRET, value: "s3cret" }) { id } }""",
            "createVariable.id",
        )
        // Not the borrower's before it is lent: a connection there naming it reads nothing.
        assertThat(secretVariables.find(borrower, token)).isNull()

        val lent = share("VARIABLE_CATALOG", catalog).path("shareWithWorkspace.id").entity(Long::class.java).get()
        val offered = graphQlTester.document("query { lentVariables(workspaceId: $borrower) { name catalogName value lentFrom } }").execute()
            .path("lentVariables[*].name").entityList(String::class.java).containsExactly("region", "token")
            .path("lentVariables[0].value").valueIsNull()
        assertThat(offered.path("lentVariables[0].catalogName").entity(String::class.java).get()).startsWith("Shared keys · shared from platform-")
        assertThat(offered.path("lentVariables[0].lentFrom").entity(String::class.java).get()).startsWith("platform-")
        assertThat(secretVariables.find(borrower, token)?.value).isEqualTo("s3cret")

        graphQlTester.document("mutation { removeWorkspaceShare(id: $lent) }").execute().errors().verify()
        assertThat(secretVariables.find(borrower, token)).isNull()
        graphQlTester.document("query { lentVariables(workspaceId: $borrower) { name } }").execute()
            .path("lentVariables").entityList(Any::class.java).hasSize(0)
    }

    @Test
    fun `taking a share back takes the catalog and the model from the borrower's agents`() {
        val catalogShare = share("VARIABLE_CATALOG", catalog).path("shareWithWorkspace.id").entity(Long::class.java).get()
        val providerShare = share("MODEL_PROVIDER", provider).path("shareWithWorkspace.id").entity(Long::class.java).get()
        val helper = agent()
        graphQlTester.document(
            """mutation { updateAgent(id: ${helper.id}, input: { name: "${helper.name}", modelId: $model,
                 secretCatalogs: ["share:$catalog"], variableCatalogs: ["share:$catalog"] }) { id } }""",
        ).execute().errors().verify()

        graphQlTester.document("mutation { removeWorkspaceShare(id: $catalogShare) }").execute().errors().verify()
        graphQlTester.document("mutation { removeWorkspaceShare(id: $providerShare) }").execute().errors().verify()

        val after = requireNotNull(agents.findById(requireNotNull(helper.id)).orElse(null))
        assertThat(after.variableCatalogs).isEmpty()
        assertThat(after.secretCatalogs).isEmpty()
        assertThat(after.modelId).isNull()
        assertThat(audit.findAll().filter { it.workspaceId == borrower }.map { it.message })
            .contains("Agent ${helper.name} has no model: its provider is no longer shared")

        // And a grant to a catalog nobody lends is refused rather than kept.
        graphQlTester.document(
            """mutation { updateAgent(id: ${helper.id}, input: { name: "${helper.name}", variableCatalogs: ["share:$catalog"] }) { id } }""",
        ).execute().errors().expect { it.extensions["code"] == "AgentCatalogNotLent" }
        graphQlTester.document(
            """mutation { updateAgent(id: ${helper.id}, input: { name: "${helper.name}", modelId: $model }) { id } }""",
        ).execute().errors().expect { it.message?.contains("another workspace") == true }
    }

    @Test
    fun `a share to itself, twice, or of what the workspace does not own is refused`() {
        share("VARIABLE_CATALOG", catalog, to = owner).errors().expect { it.extensions["code"] == "ShareToItself" }
        share("VARIABLE_CATALOG", catalog).errors().verify()
        share("VARIABLE_CATALOG", catalog).errors().expect { it.extensions["code"] == "ShareExists" }
        val theirs = id("""mutation { createVariableCatalog(workspaceId: $borrower, name: "Theirs") { id } }""", "createVariableCatalog.id")
        share("VARIABLE_CATALOG", theirs).errors().expect { it.extensions["code"] == "SharedComponentNotFound" }
    }

    @Test
    fun `removing a lent catalog removes its shares`() {
        share("VARIABLE_CATALOG", catalog).errors().verify()
        val variable = graphQlTester.document("query { workspaceVariables(workspaceId: $owner, catalogId: $catalog) { content { id } } }")
            .execute().path("workspaceVariables.content[0].id").entity(Long::class.java).get()
        graphQlTester.document("mutation { deleteVariable(id: $variable) }").execute().errors().verify()
        graphQlTester.document("mutation { deleteVariableCatalog(id: $catalog) }").execute().errors().verify()
        graphQlTester.document("query { workspaceSharesReceived(workspaceId: $borrower) { id } }").execute()
            .path("workspaceSharesReceived").entityList(Any::class.java).hasSize(0)
    }
}
