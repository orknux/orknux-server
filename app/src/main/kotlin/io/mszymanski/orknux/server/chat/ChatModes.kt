package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.interaction.InteractionHandler
import io.mszymanski.orknux.server.interaction.InteractionOption
import io.mszymanski.orknux.server.interaction.InteractionRequest
import io.mszymanski.orknux.server.interaction.Interactions
import io.mszymanski.orknux.server.llm.SessionEventKind
import io.mszymanski.orknux.server.llm.SessionInbox
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/**
 * What a chat's mode means for the agent answering it. Issue #653.
 *
 * The mode is the chat's, switched from its composer and kept on the row; this
 * is where it is read and where what it lends is made. Plan mode lends the one
 * tool that ends planning - putting the plan to the person - and a paragraph
 * saying the agent is in it; auto mode lends only the paragraph, because what
 * it changes is not what the agent may call but whether a call waits for a
 * person, and that is decided where the waiting is (see
 * [io.mszymanski.orknux.server.box.ExposedPorts.request]). Which tools plan
 * mode leaves in is [ToolEffects]'s to say, not this.
 *
 * A shed and not a grant: presenting a plan is the agent addressing the thing
 * running its loop, which is what [ToolShed] is for, and it is gone the moment
 * the chat leaves plan mode.
 */
@Service
class ChatModes(
    private val chats: ChatSessionRepository,
    /** A provider, because the handler that answers a plan is built on this. */
    private val interactions: ObjectProvider<Interactions>,
    private val inbox: SessionInbox,
    private val mapper: ObjectMapper,
) {

    /** The mode of the chat recording into [llmSessionId]; [ChatMode.DEFAULT] for anything that is not a chat. */
    fun of(llmSessionId: Long?): ChatMode =
        llmSessionId?.let { chats.findFirstByLlmSessionId(it)?.mode } ?: ChatMode.DEFAULT

    /** What [mode] lends [agent] for one round in the conversation [session]; null for the default. */
    fun shed(mode: ChatMode, agent: Agent, session: Long?): ToolShed? = when (mode) {
        ChatMode.DEFAULT -> null
        ChatMode.PLAN -> Planning(agent, session)
        ChatMode.AUTO -> Going
    }

    private inner class Planning(private val agent: Agent, private val session: Long?) : ToolShed {
        override fun specs(): List<ToolSpec> = listOf(PRESENT_SPEC)

        override fun briefing(): String = PLAN_BRIEFING

        override fun handles(name: String): Boolean = name == PRESENT

        override fun run(call: ToolCall): String {
            fun refuse(said: String) = mapper.writeValueAsString(mapOf("error" to said))
            val conversation = session ?: return refuse("There is no conversation to present a plan in")
            val args = runCatching { mapper.readTree(call.arguments.ifBlank { "{}" }) }.getOrNull()
                ?: return refuse("The arguments were not a JSON object")
            val plan = args.path(PLAN).takeIf { it.isString }?.stringValue()?.trim()?.ifEmpty { null }
                ?: return refuse("Write the $PLAN to present")
            if (plan.length > MOST_PLAN_CHARS) return refuse("Keep the $PLAN under $MOST_PLAN_CHARS characters")
            val asked = interactions.getObject().ask(
                workspaceId = agent.workspaceId,
                llmSessionId = conversation,
                kind = KIND,
                subjectId = agent.id,
                askedBy = agent.name,
                question = plan,
                options = listOf(
                    InteractionOption(APPROVE, "Approve", "primary"),
                    InteractionOption(KEEP_PLANNING, "Keep planning"),
                ),
                // So "keep planning" can say what to change, in the person's own words.
                allowsText = true,
            )
            return mapper.writeValueAsString(
                mapOf(
                    "presented" to asked.id,
                    "status" to "pending",
                    "note" to "The person has been shown your plan, with a button to approve it and one to keep " +
                        "planning. Their answer reaches you later; end your turn now, without repeating the plan.",
                ),
            )
        }
    }

    /** Auto mode lends nothing but its paragraph. */
    private object Going : ToolShed {
        override fun specs(): List<ToolSpec> = emptyList()

        override fun briefing(): String = AUTO_BRIEFING

        override fun handles(name: String): Boolean = false

        override fun run(call: ToolCall): String = error("Auto mode lends no tools")
    }

    /**
     * What the person decided about a plan, handed to the agent. Approving takes
     * the chat out of plan mode - unless somebody already switched it to
     * something else - and gives the agent the plan back as the next thing to
     * do; through the inbox, which wakes a chat that has gone quiet.
     */
    fun answered(request: InteractionRequest, options: List<String>, text: String?, by: String): String {
        val said = text?.let { " They added: \"$it\"." }.orEmpty()
        if (APPROVE in options) {
            chats.findFirstByLlmSessionId(request.llmSessionId)?.takeIf { it.mode == ChatMode.PLAN }?.let { chat ->
                chat.mode = null
                chats.save(chat)
            }
            inbox.post(
                request.llmSessionId,
                SessionEventKind.ANSWER,
                "$by approved your plan.$said This conversation has left plan mode, and every tool you hold " +
                    "runs again. Carry the plan out now, step by step:\n\n${request.question}",
            )
            return "Approved"
        }
        inbox.post(
            request.llmSessionId,
            SessionEventKind.ANSWER,
            "$by did not approve your plan yet and asked you to keep planning.$said Look further where it needs it, " +
                "then present the plan again.",
        )
        return listOfNotNull("Keep planning".takeIf { KEEP_PLANNING in options }, text?.let { "\"$it\"" }).joinToString(", ")
    }

    companion object {
        const val PRESENT = "present_plan"
        const val PLAN = "plan"
        const val KIND = "agent.plan"
        const val APPROVE = "approve"
        const val KEEP_PLANNING = "keep-planning"

        /** A plan somebody reads before approving, not a document. */
        private const val MOST_PLAN_CHARS = 20_000

        val PRESENT_SPEC = ToolSpec(
            name = PRESENT,
            // Asking changes nothing: the plan is only shown, and only the person's answer moves anything.
            readOnly = true,
            description = "Puts your plan to the person in this conversation, with a button to approve it and " +
                "one to keep planning. Call it once you know what you would do. The answer reaches you later: " +
                "after calling it, end your turn without writing the plan out again.",
            parameters = listOf(
                ToolParameterSpec(
                    name = PLAN,
                    description = "The plan, in markdown: what you found in a few lines, then the steps you would " +
                        "take, each saying what it changes.",
                    required = true,
                ),
            ),
            summary = "put your plan to the person for approval",
        )

        /** Read off the tool's own declaration, so a rename carries into what the model is told. */
        val PLAN_BRIEFING: String =
            "This conversation is in plan mode. Only the tools that change nothing will run; everything " +
                "else you hold is refused until the person approves a plan, so do not try to make the change and " +
                "do not ask for the withheld tools. Look into what was asked, then put your plan to the person " +
                "with ${PRESENT_SPEC.name}: what you found, then the steps, each saying what it will change. If " +
                "the person asks something instead, answer it and stay in plan mode."

        val AUTO_BRIEFING: String =
            "This conversation is in auto mode. Carry the work through with the tools you hold without stopping " +
                "to ask the person whether to go on; a step that would wait for their leave goes ahead. Ask only " +
                "where you need a decision that is theirs to make. What you hold is unchanged: auto mode gives you " +
                "no tool you were not granted."
    }
}

/** Approve, or keep planning, as the person answered the plan in the chat. */
@Component
class PlanApprovals(private val modes: ChatModes) : InteractionHandler {
    override val kind = ChatModes.KIND

    override fun answered(request: InteractionRequest, option: String, by: String): String =
        modes.answered(request, listOf(option), null, by)

    override fun answered(request: InteractionRequest, options: List<String>, text: String?, by: String): String =
        modes.answered(request, options, text, by)
}
