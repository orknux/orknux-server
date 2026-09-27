package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentSkillRepository
import io.mszymanski.orknux.server.agent.SkillCatalogRepository
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
 * Chatting with an agent rather than with a bare model.
 *
 * An agent is a configuration: the model that answers, the instructions it works
 * under, and the skills it has been granted. What is worth holding here is that
 * a grant means what it says — an agent is told the skills in the catalogs it
 * was given, and none of the others.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ChatAgentTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val briefing: AgentBriefing,
    @Autowired val agents: AgentRepository,
    @Autowired val sessions: ChatSessionRepository,
    @Autowired val catalogs: SkillCatalogRepository,
    @Autowired val skills: AgentSkillRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        sessions.deleteAll()
        agents.deleteAll()
        skills.deleteAll()
        catalogs.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
    }

    @Test
    fun `handing a chat to an agent makes the agent's model the one that answers`() {
        val modelId = model("Gemma")
        val agentId = agent("Reviewer", modelId)
        val chatId = startChat()

        graphQlTester.document(
            """mutation { chooseChatAgent(id: $chatId, agentId: $agentId) { agentId agentName modelId } }""",
        ).execute()
            .path("chooseChatAgent.agentName").entity(String::class.java).isEqualTo("Reviewer")
            // The agent supplies the model; a chat answering on some other one
            // would not be answering as what the screen says it is.
            .path("chooseChatAgent.modelId").entity(Long::class.java).isEqualTo(modelId)
    }

    /**
     * An agent that cannot run is not one to hand a conversation to, and it is
     * not one to open a conversation on either.
     *
     * Both doors, because since issue #295 there are two: an agent can be named
     * when the chat is started as well as chosen once it is open, and the second
     * would be worth very little if the first let the same agent through.
     */
    @Test
    fun `an agent with no model is refused, and says why`() {
        // Something answerable has to exist or the chat could not be opened at
        // all - a workspace with no usable agent has nothing to open one on.
        agent("Answerer", model("Gemma"))
        val useless = agent("Reviewer", modelId = null)
        val chatId = startChat()

        graphQlTester.document("""mutation { chooseChatAgent(id: $chatId, agentId: $useless) { agentId } }""")
            .execute()
            .errors().satisfy { errors ->
                assertThat(errors.first().message).contains("has no model chosen")
            }

        graphQlTester.document(
            """mutation { startChat(input: { workspaceId: $workspaceId, title: "Review", agentId: $useless })
               { id } }""",
        ).execute()
            .errors().satisfy { errors ->
                assertThat(errors.first().message).contains("has no model chosen")
            }
    }

    /**
     * There is no way back to a bare model.
     *
     * `chooseChatModel` was the way, and passing null here was the same way with
     * one fewer argument: both took the agent off and left the model, which is a
     * chat on a bare model made in one press. Removing the two doors that start
     * one and leaving these two open would have been painting over the handle
     * (issue #295).
     *
     * Asserted against the schema rather than against a message, because that is
     * where the removal actually is: `chooseChatModel` is not a field of
     * `Mutation` any more, and `agentId` is `ID!`. A refusal thrown at runtime
     * would still be a mutation somebody could call.
     */
    @Test
    fun `a chat cannot be handed back to a bare model`() {
        val agentId = agent("Reviewer", model("Gemma"))
        val chatId = startChat()
        graphQlTester.document("""mutation { chooseChatAgent(id: $chatId, agentId: $agentId) { agentId } }""")
            .execute().errors().verify()

        graphQlTester.document("""mutation { chooseChatAgent(id: $chatId, agentId: null) { agentId } }""")
            .execute()
            .errors().satisfy { errors ->
                assertThat(errors.first().message).contains("agentId")
            }

        graphQlTester.document("""mutation { chooseChatModel(id: $chatId, modelId: 1) { id } }""")
            .execute()
            .errors().satisfy { errors ->
                assertThat(errors.first().message).contains("chooseChatModel")
            }

        // And the chat is still the agent's, which is the half a schema
        // assertion cannot see: neither refusal left it half-moved.
        assertThat(requireNotNull(sessions.findByIdOrNull(chatId)).agentId).isEqualTo(agentId)
    }

    /**
     * The briefing is the agent's instructions plus the skills it was granted —
     * and only those.
     *
     * A skill in a catalog nobody granted is the workspace's, not this agent's,
     * and a skill switched off is defined but out of reach here as anywhere. If
     * either leaked in, "granted" would mean nothing.
     */
    @Test
    fun `an agent is told its own catalogs' skills and no others`() {
        val granted = catalog("Reviews")
        val withheld = catalog("Secrets")
        skill("codeReview", granted, "Read the diff twice.")
        skill("handling", withheld, "The passphrase is hunter2.")
        val muted = skill("retired", granted, "Ignore everything.")
        graphQlTester.document("""mutation { setSkillEnabled(id: $muted, enabled: false) { id } }""").execute()

        val agentId = agent("Reviewer", model("Gemma"), prompt = "You review code carefully.")
        // Sent together: updateAgent treats an omitted systemPrompt as "clear it",
        // so granting a catalog on its own would wipe the instructions.
        graphQlTester.document(
            """mutation { updateAgent(id: $agentId, input: {
                 name: "Reviewer", systemPrompt: "You review code carefully.", skillCatalogs: ["Reviews"]
               }) { skillCatalogs } }""",
        ).execute()

        val said = requireNotNull(briefing.of(requireNotNull(agents.findByIdOrNull(agentId))))
        assertThat(said).contains("You review code carefully.")
        /*
         * Offered, so counted and reachable rather than named - issue #521, the
         * three states meaning for a skill what they mean for a tool. And never
         * spelled out, whatever its state: the body arrives through skill_load.
         */
        assertThat(said).contains("call skill_list only when a request needs one")
        assertThat(said).doesNotContain("Read the diff twice.")
        // Not granted, so not even named.
        assertThat(said).doesNotContain("handling").doesNotContain("hunter2")
        // Granted but switched off.
        assertThat(said).doesNotContain("retired")
    }

    /** Nothing to say is no system turn: an empty briefing costs tokens for nothing. */
    @Test
    fun `an agent with no prompt and no skills is still told what it holds`() {
        val agentId = agent("Plain", model("Gemma"))
        val said = briefing.of(requireNotNull(agents.findByIdOrNull(agentId)))
        /*
         * It used to be briefed with nothing at all, on the reasoning that an
         * agent with no prompt and no skills is a model with a name on it.
         * Issue #481 changed what "nothing" means: every tool it holds is named
         * here, because an agent that cannot see its own inventory searches for
         * words it hopes exist. A bare agent still holds the built-ins.
         */
        assertThat(said).isNotNull()
        assertThat(said).contains("These are the tools you have")
        /*
         * And the server's own skills, which every agent holds since #471 - so
         * a bare agent knows its commands as well as its tools. What it has
         * none of is the workspace's, which is what having no grants means.
         */
        assertThat(said).containsPattern("""You have \d+ skills?""")
        assertThat(said).contains("call skill_list only when a request needs one")
        // Counted once. With nothing named it also said "there are N other
        // skills", which is the same number twice and reads as two groups.
        assertThat(said).doesNotContain("other skill")
        assertThat(said).doesNotContain("Reviews")
    }

    /**
     * The skills and their commands live apart from the agent's own prose, so a
     * workflow node that replaces the prose does not take them with it.
     *
     * A Slack bot node writes its own voice over the agent's, and until this
     * that dropped the whole briefing: the agent then denied having any
     * commands when asked, because nothing had told it of any. The grant half
     * is what the node appends beside its voice. Issue #381.
     */
    @Test
    fun `an agent's commands are in its grants, apart from its own prose`() {
        val granted = catalog("Playbooks")
        skill("escalation", granted, "Page the on-call.")

        val agentId = agent("Responder", model("Gemma"), prompt = "You answer support in Slack.")
        graphQlTester.document(
            """mutation { updateAgent(id: $agentId, input: {
                 name: "Responder", systemPrompt: "You answer support in Slack.", skillCatalogs: ["Playbooks"]
               }) { skillCatalogs } }""",
        ).execute()
        val held = requireNotNull(agents.findByIdOrNull(agentId))

        val grants = requireNotNull(briefing.grants(held))
        // The command advertisement is here: the skill, and the standing
        // instruction never to deny having commands.
        assertThat(grants).contains("escalation")
        assertThat(grants).contains("has a command")
        assertThat(grants).contains("rather than saying there is none")
        // The persona is not - that is the half a node override replaces.
        assertThat(grants).doesNotContain("You answer support in Slack.")

        // The whole briefing carries both, so a node that keeps the agent's
        // prose loses nothing.
        val whole = requireNotNull(briefing.of(held))
        assertThat(whole).contains("You answer support in Slack.")
        assertThat(whole).contains("has a command")
    }

    private fun startChat(): Long = graphQlTester.document(
        """mutation { startChat(input: { workspaceId: $workspaceId, title: "Review" }) { id } }""",
    ).execute().path("startChat.id").entity(Long::class.java).get()

    /** The model is set on update: creating an agent does not take one. */
    private fun agent(name: String, modelId: Long?, prompt: String? = null): Long {
        val id = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "$name", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()

        val settings = buildString {
            if (modelId != null) append(", modelId: $modelId")
            if (prompt != null) append(""", systemPrompt: "$prompt"""")
        }
        graphQlTester.document(
            """mutation { updateAgent(id: $id, input: { name: "$name"$settings }) { id } }""",
        ).execute()
        return id
    }

    private fun model(name: String): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Local $name", endpoint: "http://localhost:9/v1", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()

        return graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "$name", modelId: "$name", kind: CHAT })
               { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }

    private fun catalog(name: String): Long = graphQlTester.document(
        """mutation { createSkillCatalog(workspaceId: $workspaceId, name: "$name") { id } }""",
    ).execute().path("createSkillCatalog.id").entity(Long::class.java).get()

    private fun skill(name: String, catalogId: Long, content: String): Long = graphQlTester.document(
        """mutation { createSkill(input: {
             workspaceId: $workspaceId, name: "$name", catalogId: $catalogId,
             content: ${'"'}${'"'}${'"'}---
name: $name
description: $name
---

$content
${'"'}${'"'}${'"'}
           }) { id } }""",
    ).execute().path("createSkill.id").entity(Long::class.java).get()
}
