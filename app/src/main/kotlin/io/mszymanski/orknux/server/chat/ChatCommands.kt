package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.server.mcp.OrknuxScope
import io.mszymanski.orknux.server.mcp.OrknuxTools
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/**
 * The commands somebody can type instead of saying something.
 *
 * ### What it is for
 *
 * A chat is for asking an agent, and some of what people want from this product
 * is not a question: start that workflow, file that as an issue. Doing either
 * meant leaving the conversation, finding the page and coming back - and the
 * conversation is usually where the reason lives, so what came back was somebody
 * retyping what they had just written.
 *
 * ### Why the catalogue is here rather than in the browser
 *
 * Because the chat is not the only place people type. A Slack channel is the
 * other one, and Slack's own slash commands arrive at the server with nothing of
 * the browser about them - so a list of commands written in the interface could
 * be reached from exactly one of the two places they belong.
 *
 * So the commands that *do* something live here and the chat asks for them. What
 * the interface adds on top is its own: starting a new chat or opening the find
 * box are things a browser does and a Slack message cannot ask for, and they are
 * listed by the chat rather than by this.
 *
 * ### Why each one is an orknux tool underneath
 *
 * [OrknuxTools] already implements every one of these, for the agents. Running a
 * workflow from a command and running one from an agent are the same act with
 * the same consequences - it really runs, and if the workflow messages somebody
 * it messages them - so they are the same code, with the same scope deciding
 * what may be done and the same name going on the result. A second
 * implementation would be a second set of rules about what a run is allowed to
 * do, and the two would drift.
 */
@Service
class ChatCommands(
    private val orknux: OrknuxTools,
    private val mapper: ObjectMapper,
) {

    /** What can be typed, in the order the menu offers it. */
    fun commands(): List<ChatCommandView> = CATALOGUE

    /**
     * Runs one, as the person who typed it.
     *
     * The scope is theirs: [OrknuxScope.mayWrite] because a command that only
     * read would be a command nobody needs - the pages already show everything -
     * and `watched` because there is somebody at a screen, which is the whole
     * difference between this and an agent running one of these in a workflow.
     *
     * The answer is whatever the tool said, which is JSON. The chat shows it as
     * the reply to what was typed; a Slack handler would post the same thing.
     * Nothing here formats it, because the two surfaces will not want it
     * formatted the same way.
     */
    fun run(workspaceId: Long, actor: String, name: String, argument: String?): String {
        /*
         * The slash taken off once, and used everywhere after.
         *
         * Typed with it or without it: both are what somebody means, and Slack
         * will hand it over stripped while the chat has it in the box. Stripping
         * at the comparison alone left the refusal saying "there is no
         * //teleport", which is the sentence answering a question nobody asked.
         */
        val wanted = name.trim().removePrefix("/")
        val command = CATALOGUE.firstOrNull { it.name.equals(wanted, ignoreCase = true) }
            ?: return refusal(
                "There is no /$wanted. Try: " + CATALOGUE.joinToString(", ") { "/${it.name}" },
            )

        val said = argument?.trim().orEmpty()
        if (command.argument != null && said.isEmpty()) {
            return refusal("/${command.name} needs ${command.argument}.")
        }

        val arguments = mapper.writeValueAsString(
            if (command.argument == null) emptyMap() else mapOf(command.parameter to said),
        )

        return orknux.run(
            OrknuxScope(workspaceId = workspaceId, mayWrite = true, watched = true, actor = actor),
            command.tool,
            arguments,
        )
    }

    private fun refusal(said: String): String = mapper.writeValueAsString(mapOf("error" to said))

    private companion object {

        /**
         * Deliberately short.
         *
         * Every one of these is a thing people were leaving the conversation to
         * do, and each is one orknux tool with one obvious argument. A command
         * that needed a form is a command that wanted a page, and the commands
         * that only read something are the ones a page already answers better.
         */
        val CATALOGUE = listOf(
            ChatCommandView(
                name = "workflow",
                summary = "Start a workflow",
                argument = "the workflow's name",
                parameter = "workflow",
                tool = "orknux_run_workflow",
                /*
                 * Said on the row rather than only in the result. It really
                 * runs: if the workflow messages somebody, it messages them,
                 * and that is worth knowing before pressing rather than after.
                 */
                warning = "This really runs it.",
            ),
            ChatCommandView(
                name = "issue",
                summary = "File an issue",
                argument = "one line saying what it is",
                parameter = "title",
                tool = "orknux_open_issue",
            ),
            ChatCommandView(
                name = "runs",
                summary = "The most recent runs in this workspace",
                argument = null,
                parameter = "",
                tool = "orknux_executions",
            ),
        )
    }
}

/**
 * One command, as the menu draws it and as the server resolves it.
 *
 * [parameter] and [tool] are how it is carried out and are not shown: which
 * orknux tool answers a command is this file's business, and a menu naming
 * `orknux_run_workflow` would be a menu about the implementation.
 */
data class ChatCommandView(
    /** What is typed after the slash, and what identifies it. */
    val name: String,
    /** One line, as the menu lists it. */
    val summary: String,
    /** What to type after it, in words; null where it takes nothing. */
    val argument: String?,
    /** Which of the tool's parameters the argument becomes. */
    val parameter: String,
    /** The orknux tool that does the work; see the class note. */
    val tool: String,
    /** What is worth knowing before pressing it, where anything is. */
    val warning: String? = null,
)
