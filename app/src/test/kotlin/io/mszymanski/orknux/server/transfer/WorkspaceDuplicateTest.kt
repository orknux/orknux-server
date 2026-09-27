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

    @Test
    fun `a component that cannot come is named, and the rest of the copy is made`() {
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

        val copy = graphQlTester.document(
            """mutation { duplicateWorkspace(id: $source, name: "dup-copy-$stamp") {
                 workspace { id } carried { kind count } problems } }""",
        ).execute()
        copy.errors().verify()

        val problems = copy.path("duplicateWorkspace.problems").entityList(String::class.java).get()
        assertThat(problems).anyMatch { it.contains("Tester $stamp") }
        assertThat(workspaces.findByName("dup-copy-$stamp")).isNotNull()
        val copiedId = copy.path("duplicateWorkspace.workspace.id").entity(Long::class.java).get()
        assertThat(functions.findByWorkspaceIdAndName(copiedId, "greet$stamp")).isNotNull()
    }
}
