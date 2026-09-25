package io.mszymanski.orknux.server.mcp

import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.action.WorkflowFunction
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentTool
import io.mszymanski.orknux.server.agent.AgentToolRepository
import io.mszymanski.orknux.server.obj.ObjectProperty
import io.mszymanski.orknux.server.obj.PropertyKind
import io.mszymanski.orknux.server.obj.WorkflowObjectRepository
import org.springframework.data.repository.findByIdOrNull
import io.mszymanski.orknux.server.security.WebProperties
import io.mszymanski.orknux.server.variable.WorkspaceVariableRepository
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflowRepository
import io.mszymanski.orknux.workflow.execution.ExecutionService
import io.mszymanski.orknux.workflow.execution.ExecutionStatus
import io.mszymanski.orknux.workflow.execution.ExecutionTrigger
import io.mszymanski.orknux.workflow.execution.StartExecutionInput
import org.springframework.context.annotation.Lazy
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import io.mszymanski.orknux.server.revision.ComponentRevisionRecorder
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.CompletableFuture

/**
 * Who is asking, and what they are allowed to ask for.
 *
 * Everything here is scoped to exactly one workspace, and the scope is passed in
 * rather than read from the security context — because there is not always a
 * person there. An agent running inside a workflow has no session at all; what
 * authorises it is the grant on the agent, and what bounds it is the workspace
 * it belongs to. A person asking through the quick chat or over MCP has both,
 * and their own access is checked before a scope is built for them.
 *
 * That is the whole of the rule: nothing in here decides who may do what. It is
 * told, by whichever door the caller came through.
 */
data class OrknuxScope(
    val workspaceId: Long,
    /**
     * Whether anything that *changes* something may be called.
     *
     * Reading what a workflow did and running it, or turning it off, are
     * different orders of consequence — a run can message a customer, and a
     * workflow switched off stops answering its trigger — so they are separated
     * here rather than trusted to the model's judgement about what it was
     * asked.
     *
     * It does not stretch to deleting anything. Nothing here removes a
     * workflow, a run or a credential, whatever this says.
     */
    val mayWrite: Boolean = false,
    /**
     * Whether there is somebody at a screen to be shown something.
     *
     * Suggesting a change is not the same kind of act as reading or running:
     * it produces nothing on its own and waits for a person to accept it. An
     * agent inside a workflow has nobody to ask, so it is not offered the tool
     * - being told it can propose a change that nothing will ever show is
     * worse than not having it.
     */
    val watched: Boolean = false,
    /**
     * Whose name goes on what these tools change, when nobody is signed in.
     *
     * An agent working a task reaches the tracker through here in-process,
     * with no session and no security context behind it, so every comment it
     * left and every status it moved was filed under the product's own name -
     * a tracker in which "orknux commented" is the only thing an issue's
     * history can say about work an agent did (issue #230). The agent has a
     * name, somebody chose it, and it is the true answer to who wrote that.
     *
     * Null wherever there *is* somebody signed in - the MCP endpoint, where a
     * token carries its owner. The security context wins over this on purpose:
     * a name passed in an argument must never be able to sign somebody else's
     * name to a change.
     */
    val actor: String? = null,
)

/**
 * A change offered for a function's code: shown, never saved here.
 *
 * Carried out of the tool loop rather than written down, because what happens
 * to it is a person's decision and the conversation is where they make it.
 */
data class FunctionSuggestion(
    val functionId: Long,
    val name: String,
    val note: String?,
    val code: String,
)

/**
 * The same offer, made about a tool's code.
 *
 * Kept apart from [FunctionSuggestion] rather than folded into it because they
 * land in different places: a function's change is shown by the function editor
 * and saved with a parameter list read off the declaration, and a tool declares
 * none — it is a default export taking whatever the agent composed. What they
 * share is the only thing that matters here: nothing is written until somebody
 * presses accept.
 */
data class ToolSuggestion(
    val toolId: Long,
    val name: String,
    val note: String?,
    val code: String,
)

/**
 * What orknux can be asked about itself.
 *
 * One surface, three doors: an agent granted it in its settings, the quick chat
 * beside the interface, and the MCP endpoint an outside agent connects to. They
 * differ in how the caller is identified and in nothing else, so the tools are
 * defined once here rather than three times behind three protocols.
 *
 * Every answer is JSON, because every caller is a model.
 */
@Service
class OrknuxTools(
    private val assignments: WorkspaceWorkflowRepository,
    /**
     * Resolved when a tool is called rather than when this is built.
     *
     * There is a real cycle here, and it is the domain's rather than an
     * accident of wiring: an agent may start a workflow, and a workflow may run
     * an agent. Following it through the beans —
     * `ExecutionService → InlineExecutionEngine → StepRunner → AgentNodeRunner →
     * AgentConversation → AgentTools → OrknuxTools` — arrives back here, and
     * Spring refuses to build a context containing that.
     *
     * It only closes with the inline engine, since the Temporal one starts runs
     * through a worker instead of calling the step runner directly. That is why
     * it was invisible on a machine with Temporal up, and why the first thing
     * that noticed was a container started without it.
     *
     * Lazy is not a workaround here so much as the accurate statement: nothing
     * in this class needs the engine to exist until somebody asks it to start
     * something.
     */
    @param:Lazy private val runs: ExecutionService,
    private val agents: AgentRepository,
    /**
     * The workspace's own tools — the code an agent calls, not the tools in
     * this class. Read here for the same reason functions are: somebody at the
     * tool editor asking for help is asking about code that is stored, and a
     * model that cannot read it can only describe what a tool of that name
     * might contain.
     */
    private val agentTools: AgentToolRepository,
    private val functions: WorkflowFunctionRepository,
    /**
     * The shapes the workspace has named, for the parameters that name one.
     *
     * A signature says `payload: SlackMessage` and stops there, which tells a
     * model that something called SlackMessage exists and nothing about what is
     * in it - so the code it writes reaches for fields it has invented. The
     * object is where the fields are written down, descriptions included, and
     * this is what reads them.
     */
    private val objects: WorkflowObjectRepository,
    /**
     * For the names of the variables a function is handed, and nothing else.
     *
     * Their values are never read here. What a model writing a function needs is
     * that `agentName` arrives after the declared parameters and under that name -
     * a list of ids tells it that there is one and leaves it guessing which entry
     * in the declaration that is.
     */
    private val variables: WorkspaceVariableRepository,
    private val revisions: ComponentRevisionRecorder,
    private val issueTools: IssueTools,
    private val newsTools: NewsTools,
    private val web: WebProperties,
    private val mapper: ObjectMapper,
) {

    /**
     * Where a thing can be opened, sent back with the thing itself.
     *
     * A model that says "run 20 completed" has told somebody a fact and left
     * them to go and find it; one that links to run 20 has finished the job. So
     * every answer that names something with a page of its own carries the
     * address of that page, and the briefing asks for it to be used.
     *
     * Absolute, from the origin the interface is served on. A relative path
     * works in the panel, which is already on that origin, and is useless to
     * anything reaching this over MCP from somewhere else — and those are the
     * same tools. Where no origin is configured the path is all there is to
     * give, which is still better than nothing.
     */
    private fun link(path: String): String {
        val base = web.allowedOrigins.firstOrNull()?.trimEnd('/').orEmpty()
        return base + path
    }

    private fun runLink(workspaceId: Long, id: Long?) = link("/workspace/$workspaceId/executions/$id")

    private fun workflowLink(workspaceId: Long, id: Long?) = link("/workspace/$workspaceId/workflows/$id/editor")

    private fun agentLink(workspaceId: Long, id: Long?) = link("/workspace/$workspaceId/agents/$id/settings")

    private fun functionLink(workspaceId: Long, id: Long?) = link("/workspace/$workspaceId/functions/$id")

    private fun toolLink(workspaceId: Long, id: Long?) = link("/workspace/$workspaceId/tools/$id")

    /** By number, because that is what the address carries and what people say. */
    private fun issueLink(workspaceId: Long, number: Int) = link("/workspace/$workspaceId/issues/$number")

    /** What to offer, which depends on whether this caller may start anything. */
    fun specs(scope: OrknuxScope): List<ToolSpec> = buildList {
        add(
            ToolSpec(
                name = "orknux_workflows",
                description = "The workflows in this workspace, whether each is enabled, and where it last got to.",
                parameters = emptyList(),
            ),
        )
        add(
            ToolSpec(
                name = "orknux_executions",
                description = "Recent runs in this workspace, newest first.",
                parameters = listOf(
                    ToolParameterSpec(
                        "status",
                        "Only runs in this state: RUNNING, COMPLETED, FAILED, STOPPED.",
                        required = false,
                    ),
                    ToolParameterSpec("limit", "How many to return; 20 by default, 100 at most.", required = false),
                ),
            ),
        )
        add(
            ToolSpec(
                name = "orknux_execution",
                description = "One run in full: every step, what it produced, and what failed.",
                parameters = listOf(ToolParameterSpec("id", "The run's id.", required = true)),
            ),
        )
        add(
            ToolSpec(
                name = "orknux_agents",
                description = "The agents configured in this workspace.",
                parameters = emptyList(),
            ),
        )

        /*
         * The code, which is the thing most often being asked about.
         *
         * Somebody with a function open and a question about it was told there
         * was no way to see inside one - which was true, and left the model
         * discussing code it could not read. Two tools rather than one: the list
         * is what a model needs to find the right function, and the source is
         * long enough that fetching every function's to answer a question about
         * one would fill the conversation with the others.
         */
        /*
         * The tracker, which is where the work is decided.
         *
         * An assistant that can read what it has been asked to do - and say
         * what it did about it, in the same place the person will look - is
         * working the way everybody else on the team works. Without these it is
         * told about an issue in a chat window that nobody else can see.
         */
        add(
            ToolSpec(
                name = "orknux_issues",
                description = "Issues in this workspace. Filter by who they are assigned to, by state, or by words.",
                parameters = listOf(
                    ToolParameterSpec("assignee", "Only issues assigned to this name - a person, agent or model.", required = false),
                    ToolParameterSpec("status", "OPEN or CLOSED; both when absent.", required = false),
                    ToolParameterSpec(
                        "type",
                        "Only issues of this type, by name - `bug`, `feature`, or whatever else this " +
                            "workspace files; `untyped` for the ones nobody has classified. " +
                            "`orknux_issue_types` lists them.",
                        required = false,
                    ),
                    ToolParameterSpec("search", "Words to look for in the title, the description or the labels.", required = false),
                    ToolParameterSpec(
                        "labels",
                        "Only issues carrying all of these labels, comma separated - `p1` for the urgent ones.",
                        required = false,
                    ),
                ),
            ),
        )
        add(
            ToolSpec(
                name = "orknux_issue_labels",
                description =
                    "The labels this workspace uses and how many issues carry each. A label only works as a " +
                        "filter if you know it is there.",
                parameters = emptyList(),
            ),
        )
        add(
            ToolSpec(
                name = "orknux_issue_types",
                description =
                    "The kinds of thing this workspace files - bug, feature, or whatever else it decided - " +
                        "and how many issues carry each. Read it before filing: a type is chosen from this " +
                        "list and cannot be invented, unlike a label.",
                parameters = emptyList(),
            ),
        )
        add(
            ToolSpec(
                name = "orknux_issue",
                description =
                    "One issue in full, with its description, every comment, and what it has to do with " +
                        "other issues - read that before starting on it, since one that is blocked or is a " +
                        "duplicate says so there and nowhere else.",
                parameters = listOf(ToolParameterSpec("issue", "Its number in this workspace, like 4.", required = true)),
            ),
        )

        if (scope.mayWrite) {
            add(
                ToolSpec(
                    name = "orknux_open_issue",
                    description =
                        "Files a new issue in this workspace, under your own name. Use it for anything worth " +
                            "somebody else seeing: what you found, what you could not do, what should be decided.",
                    parameters = listOf(
                        ToolParameterSpec(
                            "title",
                            // The limit is said here as well as enforced, so the ordinary
                            // case is a title that fits rather than one refused and rewritten.
                            "One line saying what it is, 200 characters at most.",
                            required = true,
                        ),
                        ToolParameterSpec("description", "The detail; markdown is rendered.", required = false),
                        ToolParameterSpec(
                            "type",
                            "What kind of thing it is, by name - `bug` if something is broken, `feature` if " +
                                "something is missing. One of what `orknux_issue_types` lists; left out, it " +
                                "is filed untyped rather than guessed at.",
                            required = false,
                        ),
                        ToolParameterSpec(
                            "labels",
                            "Labels to file it under, comma separated; 60 characters each at most.",
                            required = false,
                        ),
                        ToolParameterSpec(
                            "observers",
                            "Who should hear about it, comma separated - people or agents, by name. They are " +
                                "told it exists and hear everything said on it afterwards, without being given " +
                                "the work. Name whoever should see this. Left out, the workspace's " +
                                "administrators are told, because an issue nobody hears about is a report " +
                                "nobody read.",
                            required = false,
                        ),
                    ),
                ),
            )
            add(
                ToolSpec(
                    name = "orknux_comment_on_issue",
                    description = "Says something on an issue, under your own name. Everybody who reads it sees it.",
                    parameters = listOf(
                        ToolParameterSpec("issue", "Its number in this workspace.", required = true),
                        ToolParameterSpec("content", "What to say. Markdown is rendered.", required = true),
                    ),
                ),
            )
            add(
                ToolSpec(
                    name = "orknux_set_issue_status",
                    description = "Opens an issue, says somebody is working on it, or closes it.",
                    parameters = listOf(
                        ToolParameterSpec("issue", "Its number in this workspace.", required = true),
                        ToolParameterSpec(
                            "status",
                            "OPEN, IN_PROGRESS, REVIEW or CLOSED. IN_PROGRESS says somebody has picked it " +
                                "up and open means nobody has yet, so say it when you start rather than " +
                                "when you finish - it is how anybody else looking at the same list knows " +
                                "not to start it too. REVIEW is done but not yet shipped.",
                            required = true,
                        ),
                    ),
                ),
            )
            add(
                ToolSpec(
                    name = "orknux_update_issue",
                    description =
                        "Changes an issue's title, description, type, labels or who is on it. " +
                            "What is left out is left alone.",
                    parameters = listOf(
                        ToolParameterSpec("issue", "Its number in this workspace.", required = true),
                        ToolParameterSpec("title", "A new title, 200 characters at most.", required = false),
                        ToolParameterSpec("description", "A new description; markdown is rendered.", required = false),
                        ToolParameterSpec(
                            "type",
                            "What kind of thing it is, by name, from what `orknux_issue_types` lists. " +
                                "\"untyped\" puts it back to none.",
                            required = false,
                        ),
                        ToolParameterSpec(
                            "labels",
                            "The labels it should have, comma separated; 60 characters each at most. Replaces them all.",
                            required = false,
                        ),
                        ToolParameterSpec(
                            "add_labels",
                            "Labels to add, comma separated, leaving the rest alone; 60 characters each at most.",
                            required = false,
                        ),
                        ToolParameterSpec("remove_labels", "Labels to take off, comma separated.", required = false),
                        ToolParameterSpec(
                            "assignee",
                            "Who is on it, by name - a person, an agent or a model, matched the way the " +
                                "assignee box matches. \"nobody\" hands it back to no one.",
                            required = false,
                        ),
                    ),
                ),
            )
        }

        /*
         * The one tool that can take its time.
         *
         * Everything else here answers a question; this one waits for the
         * answer to arrive. `wait` is what makes the tracker work in both
         * directions - without it somebody has to say "I replied on #6" by
         * hand, which is the message the tracker was supposed to replace.
         */
        add(
            ToolSpec(
                name = "orknux_news",
                description =
                    "What has happened on the issues that concern you - assigned to you, closed, reopened or " +
                        "commented on - since you last read. Reading marks it read. Set `wait` to hold the " +
                        "call open until something happens: this is how you find out without being told.",
                parameters = listOf(
                    ToolParameterSpec(
                        "wait",
                        "Seconds to wait if there is nothing yet, up to 300. Absent or 0 answers straight away.",
                        required = false,
                    ),
                    ToolParameterSpec(
                        "as",
                        "An agent's name, to read what it has been sent instead of your own news.",
                        required = false,
                    ),
                ),
            ),
        )

        add(
            ToolSpec(
                name = "orknux_functions",
                description = "The functions in this workspace: what each is called, takes and gives back.",
                parameters = emptyList(),
            ),
        )
        /*
         * Offering a rewrite, where there is somebody to offer it to.
         *
         * It writes nothing. What comes back to the model is that the change
         * has been put in front of somebody, and the next thing it hears is
         * whether they took it - which is the whole of the loop this makes.
         */
        if (scope.watched) {
            add(
                ToolSpec(
                    name = "orknux_suggest_function_code",
                    description =
                        "Offers a rewrite of a function's code, shown beside what is there now for them to accept " +
                            "or reject. It does not save anything: they decide, and you are told which they chose. " +
                            "Send the whole function, not a fragment. Its parameter list comes from the " +
                            "declaration you write, so adding, removing or renaming a parameter is done by " +
                            "writing it there.",
                    parameters = listOf(
                        ToolParameterSpec("function", "The function's name, or its id.", required = true),
                        ToolParameterSpec(
                            "code",
                            "The complete new source, in the language the function is in. What its declaration " +
                                "takes is what the function takes, so annotate every parameter with its type - " +
                                "`string`, `number`, `boolean`, `Record<string, unknown>`, `unknown[]`, or the " +
                                "name of one of this workspace's objects - and keep any workspace variables it " +
                                "is handed last, in the same order and under the same names.",
                            required = true,
                        ),
                        ToolParameterSpec("note", "One line on what this changes and why.", required = false),
                    ),
                ),
            )
        }

        add(
            ToolSpec(
                name = "orknux_function",
                description = "One function in full: the code it is written in, and the fields of any of this " +
                    "workspace's objects it takes or gives back, each with what it means.",
                parameters = listOf(
                    ToolParameterSpec("function", "The function's name, or its id.", required = true),
                ),
            ),
        )

        /*
         * The workspace's tools, which are not these tools.
         *
         * The word is overloaded and the model is the one that has to live with
         * it, so both descriptions say what a tool *is* here: code an agent
         * calls, sitting under /tools, and a different thing from a function.
         * Without this a question asked on the tool editor was answered with a
         * hunt through the functions and then a refusal, which is exactly what
         * somebody standing on the page could see was wrong.
         */
        add(
            ToolSpec(
                name = "orknux_tools",
                description =
                    "The tools in this workspace: the workspace's own TypeScript that an agent may call while " +
                        "it runs. Not the same thing as a function, and not these orknux_ tools.",
                parameters = emptyList(),
            ),
        )

        if (scope.watched) {
            add(
                ToolSpec(
                    name = "orknux_suggest_tool_code",
                    description =
                        "Offers a rewrite of a tool's code, shown beside what is there now for them to accept " +
                            "or reject. It does not save anything: they decide, and you are told which they " +
                            "chose. Send the whole tool, not a fragment.",
                    parameters = listOf(
                        ToolParameterSpec("tool", "The tool's name, or its id.", required = true),
                        ToolParameterSpec(
                            "code",
                            "The complete new TypeScript. A tool is a default export taking one argument — " +
                                "whatever the agent composed — so keep that shape.",
                            required = true,
                        ),
                        ToolParameterSpec("note", "One line on what this changes and why.", required = false),
                    ),
                ),
            )
        }

        add(
            ToolSpec(
                name = "orknux_tool",
                description = "One tool in full, including the TypeScript it is written in.",
                parameters = listOf(
                    ToolParameterSpec("tool", "The tool's name, or its id.", required = true),
                ),
            ),
        )

        // Offered only where they can actually be called. A tool a model is
        // shown and then refused is a round trip spent learning what the grant
        // already said.
        if (scope.mayWrite) {
            add(
                ToolSpec(
                    name = "orknux_run_workflow",
                    description =
                        "Starts a workflow. This really runs it — if the workflow messages somebody, it messages them.",
                    parameters = listOf(
                        ToolParameterSpec("workflow", "The workflow's name, or its id.", required = true),
                        ToolParameterSpec(
                            "input",
                            "JSON handed to the first node, as a trigger would have supplied it.",
                            required = false,
                        ),
                    ),
                ),
            )
            add(
                ToolSpec(
                    name = "orknux_rerun_execution",
                    description =
                        "Runs a past run again, on the same input it was given. It acts on the same event: " +
                            "if that run answered somebody, it answers them again.",
                    parameters = listOf(ToolParameterSpec("id", "The run to repeat.", required = true)),
                ),
            )
            add(
                ToolSpec(
                    name = "orknux_set_workflow_enabled",
                    description = "Turns a workflow on or off. A workflow that is off does not run when its trigger fires.",
                    parameters = listOf(
                        ToolParameterSpec("workflow", "The workflow's name, or its id.", required = true),
                        ToolParameterSpec("enabled", "true to turn it on, false to turn it off.", required = true),
                    ),
                ),
            )
            add(
                ToolSpec(
                    name = "orknux_set_agent_enabled",
                    description = "Turns an agent on or off. An agent that is off cannot be asked anything.",
                    parameters = listOf(
                        ToolParameterSpec("agent", "The agent's name, or its id.", required = true),
                        ToolParameterSpec("enabled", "true to turn it on, false to turn it off.", required = true),
                    ),
                ),
            )
        }
    }

    /** Whether this is one of ours, so a caller knows where to send it. */
    fun handles(name: String): Boolean = name.startsWith(PREFIX)

    /**
     * Runs one call and answers as JSON when it is ready.
     *
     * The shape for a caller that has a request open and would rather not spend
     * a thread holding it: everything here answers straight away except
     * `orknux_news`, whose whole purpose is to wait, and which waits without a
     * thread. See [NewsTools].
     *
     * Never fails: a call that threw comes back as a refusal to read, the same
     * as it does through [run].
     */
    fun runAsync(scope: OrknuxScope, name: String, arguments: String): CompletableFuture<String> = try {
        when (name) {
            "orknux_news" -> newsTools.news(scope, arguments)
                .exceptionally { failure -> refuse(failure.cause?.message ?: "That could not be done") }

            else -> CompletableFuture.completedFuture(run(scope, name, arguments))
        }
    } catch (failure: Exception) {
        CompletableFuture.completedFuture(refuse(failure.message ?: "That could not be done"))
    }

    /**
     * Runs one call and answers as JSON, on this thread.
     *
     * Never throws: a model that asked for something impossible is told so and
     * can try something else, which beats ending the conversation.
     */
    fun run(scope: OrknuxScope, name: String, arguments: String): String = try {
        when (name) {
            "orknux_workflows" -> workflows(scope)
            "orknux_executions" -> executions(scope, arguments)
            "orknux_execution" -> execution(scope, arguments)
            "orknux_agents" -> agentList(scope)
            "orknux_functions" -> functionList(scope)
            "orknux_function" -> function(scope, arguments)
            "orknux_tools" -> toolList(scope)
            "orknux_tool" -> tool(scope, arguments)
            "orknux_suggest_tool_code" -> suggestTool(scope, arguments)
            /*
             * The tracker lives in its own service: writing on an issue
             * touches its comments, which are lazy, so those calls need a
             * transaction around them - and a transaction cannot be
             * started by one private method calling another in here.
             *
             * Waited out here, which is what the callers of this method want:
             * a conversation asks its tools one at a time and has nowhere to
             * be until the answer comes back. A caller with a request to hold
             * open uses [runAsync] instead and spends no thread on it.
             */
            "orknux_news" -> newsTools.news(scope, arguments).join()
            "orknux_issues" -> issueTools.list(scope, arguments)
            "orknux_issue" -> issueTools.one(scope, arguments)
            "orknux_issue_labels" -> issueTools.labels(scope)
            "orknux_issue_types" -> issueTools.types(scope)
            "orknux_open_issue" -> issueTools.open(scope, arguments)
            "orknux_comment_on_issue" -> issueTools.comment(scope, arguments)
            "orknux_set_issue_status" -> issueTools.setStatus(scope, arguments)
            "orknux_update_issue" -> issueTools.update(scope, arguments)
            "orknux_suggest_function_code" -> suggest(scope, arguments)
            "orknux_run_workflow" -> runWorkflow(scope, arguments)
            "orknux_rerun_execution" -> rerun(scope, arguments)
            "orknux_set_workflow_enabled" -> setWorkflowEnabled(scope, arguments)
            "orknux_set_agent_enabled" -> setAgentEnabled(scope, arguments)
            else -> refuse("There is no tool called $name")
        }
    } catch (failure: Exception) {
        refuse(failure.message ?: "That could not be done")
    }

    private fun workflows(scope: OrknuxScope): String {
        val held = assignments.findByWorkspaceId(scope.workspaceId, PageRequest.of(0, MANY, Sort.by("workflow.name")))
        return mapper.writeValueAsString(
            mapOf(
                "workflows" to held.content.map { assignment ->
                    val definition = assignment.workflow
                    val last = definition.id?.let { runs.lastExecution(scope.workspaceId, it) }
                    mapOf(
                        "id" to definition.id,
                        "name" to definition.name,
                        "enabled" to assignment.enabled,
                        "url" to workflowLink(scope.workspaceId, definition.id),
                        "lastRun" to last?.let {
                            mapOf(
                                "id" to it.id,
                                "status" to it.status,
                                "at" to it.startedAt,
                                "url" to runLink(scope.workspaceId, it.id),
                            )
                        },
                    )
                },
            ),
        )
    }

    private fun executions(scope: OrknuxScope, arguments: String): String {
        val asked = number(arguments, "limit")?.toInt() ?: DEFAULT_RUNS
        val page = runs.executions(
            workspaceId = scope.workspaceId,
            workflowId = null,
            status = text(arguments, "status")?.let { wanted ->
                ExecutionStatus.entries.firstOrNull { it.name.equals(wanted, ignoreCase = true) }
                    ?: return refuse("There is no run status called $wanted")
            },
            days = null,
            search = null,
            page = 0,
            size = asked.coerceIn(1, MANY),
        )
        return mapper.writeValueAsString(
            mapOf(
                "total" to page.totalElements,
                "executions" to page.content.map {
                    mapOf(
                        "id" to it.id,
                        "workflow" to it.workflowName,
                        "status" to it.status,
                        "trigger" to it.trigger,
                        "startedAt" to it.startedAt,
                        "durationSeconds" to it.durationSeconds,
                        "error" to it.error,
                        "url" to runLink(scope.workspaceId, it.id),
                    )
                },
            ),
        )
    }

    private fun execution(scope: OrknuxScope, arguments: String): String {
        val id = number(arguments, "id") ?: return refuse("Which run? Give its id.")
        val found = runs.execution(id)
            // A run in another workspace is answered exactly as one that does not
            // exist. Saying "that is not yours" confirms it is somebody's.
            ?.takeIf { it.workspaceId == scope.workspaceId }
            ?: return refuse("There is no run $id here")

        return mapper.writeValueAsString(
            mapOf(
                "id" to found.id,
                "workflow" to found.workflowName,
                "url" to runLink(scope.workspaceId, found.id),
                "workflowUrl" to workflowLink(scope.workspaceId, found.workflowId),
                "status" to found.status,
                "startedAt" to found.startedAt,
                "durationSeconds" to found.durationSeconds,
                "error" to found.error,
                "stoppedReason" to found.stoppedReason,
                "steps" to found.steps.map {
                    mapOf(
                        "name" to it.name,
                        "kind" to it.kind,
                        "status" to it.status,
                        "durationSeconds" to it.durationSeconds,
                        "output" to it.output?.take(FIELD),
                        "error" to it.error?.take(FIELD),
                    )
                },
            ),
        )
    }

    private fun agentList(scope: OrknuxScope): String {
        val held = agents.findByWorkspaceId(scope.workspaceId, PageRequest.of(0, MANY, Sort.by("name")))
        return mapper.writeValueAsString(
            mapOf(
                "agents" to held.content.map {
                    mapOf(
                        "id" to it.id,
                        "name" to it.name,
                        "description" to it.description,
                        "enabled" to it.enabled,
                        "hasModel" to (it.modelId != null),
                        "url" to agentLink(scope.workspaceId, it.id),
                    )
                },
            ),
        )
    }

    private fun functionList(scope: OrknuxScope): String {
        val held = functions.findByWorkspaceId(scope.workspaceId, PageRequest.of(0, MANY, Sort.by("name")))
        return mapper.writeValueAsString(
            mapOf(
                "functions" to held.content.map {
                    mapOf(
                        "id" to it.id,
                        "name" to it.name,
                        "description" to it.description,
                        "signature" to it.signature,
                        "returns" to it.returnType.name,
                        /*
                         * Whether this workspace may change it. A plugin's
                         * function is read here and edited where it was
                         * declared, and a model that suggests a rewrite of one
                         * is suggesting something nobody can apply.
                         */
                        "editable" to it.editable,
                        "url" to functionLink(scope.workspaceId, it.id),
                    )
                },
            ),
        )
    }

    /**
     * One function, code and all.
     *
     * The TypeScript is what is sent when there is any: it is what somebody
     * opens, and what a suggestion has to be written against. A plugin's
     * function has none, and then the JavaScript that runs is the only source
     * there is - marked as such, so a model does not offer annotations for a
     * column that cannot hold them.
     */
    private fun function(scope: OrknuxScope, arguments: String): String {
        val asked = text(arguments, "function") ?: return refuse("Which function? Give its name or id.")
        val held = functions.findByWorkspaceId(scope.workspaceId)
        val matches = held.filter { it.name.equals(asked, ignoreCase = true) || it.id?.toString() == asked }
        val chosen = when {
            matches.isEmpty() -> return refuse("There is no function called $asked here")
            matches.size > 1 -> return refuse("More than one function is called $asked; use its id")
            else -> matches.single()
        }

        return mapper.writeValueAsString(
            mapOf(
                "id" to chosen.id,
                "name" to chosen.name,
                "description" to chosen.description,
                "signature" to chosen.signature,
                "returns" to chosen.returnType.name,
                "editable" to chosen.editable,
                "language" to if (chosen.typescript != null) "typescript" else "javascript",
                "code" to (chosen.typescript ?: chosen.source),
                "parameters" to chosen.params.map { parameter ->
                    buildMap<String, Any?> {
                        put("name", parameter.name)
                        put("type", parameter.type.name)
                        shapeOf(scope, parameter.objectId)?.let { put("object", it) }
                    }
                },
                // The same for what comes back, for the same reason: a function
                // that returns a named shape is one whose `return` statement has
                // to name its fields.
                "returnedObject" to shapeOf(scope, chosen.returnObjectId),
                /*
                 * Named, not valued. An external is a workspace value and often
                 * a secret; what a model needs to write the code is that the
                 * function is handed one, under which name and in which position.
                 *
                 * In the function's own order, not the repository's: they are
                 * passed positionally, so the order is half of what is being said.
                 */
                "externals" to externalsOf(chosen),
                "url" to functionLink(scope.workspaceId, chosen.id),
            ),
        )
    }

    /**
     * One of the workspace's shapes, spelled out for a model.
     *
     * Every field with what it is and what it means, because the description is
     * the half a model cannot infer. `tier` is a word three readers read three
     * ways; "the customer's support plan - one of free, pro or enterprise" is
     * read one way by all of them. A field with nothing written on it is sent as
     * a field with nothing written on it rather than with its name repeated back
     * as prose, so what the author said and what nobody said stay distinguishable.
     *
     * Scoped like everything else here: a parameter pointing at another
     * workspace's object is answered with nothing rather than with that
     * workspace's shape.
     */
    private fun shapeOf(scope: OrknuxScope, objectId: Long?): Map<String, Any?>? {
        val shape = objectId?.let { objects.findByIdOrNull(it) } ?: return null
        if (shape.workspaceId != scope.workspaceId) return null
        val names = objects.findByWorkspaceId(scope.workspaceId).associate { it.id to it.name }
        return mapOf(
            "name" to shape.name,
            "description" to shape.description,
            "fields" to shape.properties.map { property ->
                mapOf(
                    "name" to property.name,
                    "type" to fieldType(property, names),
                    "description" to property.description,
                )
            },
        )
    }

    /** A field's type as a signature spells it: `string`, `Customer`, `array<Line>`. */
    private fun fieldType(property: ObjectProperty, names: Map<Long?, String>): String = when (property.kind) {
        PropertyKind.OBJECT -> names[property.refObjectId] ?: "object"
        PropertyKind.ARRAY -> {
            val element = property.refObjectId?.let { names[it] }
                ?: property.elementKind?.name?.lowercase()
                ?: "unknown"
            "array<$element>"
        }
        else -> property.kind.name.lowercase()
    }

    /**
     * The variables a function is handed, by name, in the order it is handed them.
     *
     * One read for all of them rather than one each, and anything that has been
     * deleted out from under the function is left out - a name it never had is
     * worse than a gap, since the model would write the code to take it.
     */
    private fun externalsOf(function: WorkflowFunction): List<Map<String, Any?>> {
        val named = variables.findAllById(function.externals.map { it.variableId }).associateBy { it.id }
        return function.externals.mapNotNull { external ->
            named[external.variableId]?.let { mapOf("name" to it.name, "type" to it.type.name) }
        }
    }

    /**
     * What the model is told when it offers a change.
     *
     * Checked here rather than at the far end: a function that is not in this
     * workspace, or that a plugin declared and nobody may edit, is a suggestion
     * that could never be taken - and the model finding that out now can say so
     * instead of showing somebody a change they cannot accept.
     */
    private fun suggest(scope: OrknuxScope, arguments: String): String {
        val offered = suggestionIn(scope, arguments) ?: return refuse(
            "Which function, and what code? Both are needed.",
        )
        return mapper.writeValueAsString(
            mapOf(
                "shown" to true,
                "function" to offered.name,
                "waiting" to "They will accept or reject it. You will be told which.",
                "url" to functionLink(scope.workspaceId, offered.functionId),
            ),
        )
    }

    /**
     * The suggestion a call is making, or null if it is not making one.
     *
     * Read by the caller as well as run, because what a suggestion is *for* is
     * outside these tools: it has to reach the screen the person is looking at,
     * and only the door they came through knows where that is.
     */
    fun suggestionIn(scope: OrknuxScope, arguments: String): FunctionSuggestion? {
        val asked = text(arguments, "function") ?: return null
        val code = text(arguments, "code")?.takeIf { it.isNotBlank() } ?: return null

        val held = functions.findByWorkspaceId(scope.workspaceId)
        val chosen = held.singleOrNull { it.name.equals(asked, ignoreCase = true) || it.id?.toString() == asked }
            ?: return null
        // A plugin's function changes where it was declared, not here.
        if (!chosen.editable) return null

        return FunctionSuggestion(
            functionId = requireNotNull(chosen.id),
            name = chosen.name,
            note = text(arguments, "note"),
            code = code,
        )
    }

    private fun toolList(scope: OrknuxScope): String {
        val held = agentTools.findByWorkspaceId(scope.workspaceId, PageRequest.of(0, MANY, Sort.by("name")))
        return mapper.writeValueAsString(
            mapOf(
                "tools" to held.content.map {
                    mapOf(
                        "id" to it.id,
                        "name" to it.name,
                        "description" to it.description,
                        // A tool switched off is still code somebody may be
                        // editing; what it means is that no agent can reach it.
                        "enabled" to it.enabled,
                        "url" to toolLink(scope.workspaceId, it.id),
                    )
                },
            ),
        )
    }

    /**
     * One tool, code and all.
     *
     * The TypeScript, never the JavaScript: what somebody has open is what they
     * wrote, and a suggestion has to be written against that. Every tool has
     * both halves — they are only ever saved together — so unlike a function
     * there is no case here where the compiled half is the only source there is.
     */
    private fun tool(scope: OrknuxScope, arguments: String): String {
        val chosen = toolCalled(scope, text(arguments, "tool"))
            ?: return refuse(toolMissing(text(arguments, "tool")))

        return mapper.writeValueAsString(
            mapOf(
                "id" to chosen.id,
                "name" to chosen.name,
                "description" to chosen.description,
                "enabled" to chosen.enabled,
                "language" to "typescript",
                "code" to chosen.typescript,
                /*
                 * Said rather than left to be inferred from the code, because
                 * the declaration in the code is one spelling of it and the
                 * stored list is the other. This is the stored one, which is
                 * what the model is shown and what the sandbox fills in order.
                 */
                "takes" to chosen.signature,
                "url" to toolLink(scope.workspaceId, chosen.id),
            ),
        )
    }

    /**
     * The tool a call is naming, by name or by id, within this workspace.
     *
     * The workspace is the boundary, and it is applied by loading that
     * workspace's tools rather than by loading the id and checking afterwards —
     * the same way functions are found, so a tool elsewhere is not there at all
     * rather than there and refused.
     */
    private fun toolCalled(scope: OrknuxScope, asked: String?): AgentTool? {
        if (asked == null) return null
        val held = agentTools.findByWorkspaceId(scope.workspaceId, PageRequest.of(0, MANY, Sort.by("name")))
        return held.content.singleOrNull { it.name.equals(asked, ignoreCase = true) || it.id?.toString() == asked }
    }

    private fun toolMissing(asked: String?): String =
        if (asked == null) "Which tool? Give its name or id." else "There is no tool called $asked here"

    /**
     * What the model is told when it offers a change to a tool.
     *
     * Nothing is written. The answer is that somebody has been shown it, and
     * the next thing the model hears is which of the two buttons they pressed.
     */
    private fun suggestTool(scope: OrknuxScope, arguments: String): String {
        val offered = toolSuggestionIn(scope, arguments) ?: return refuse(
            "Which tool, and what code? Both are needed, and the tool must be one of this workspace's.",
        )
        return mapper.writeValueAsString(
            mapOf(
                "shown" to true,
                "tool" to offered.name,
                "waiting" to "They will accept or reject it. You will be told which.",
                "url" to toolLink(scope.workspaceId, offered.toolId),
            ),
        )
    }

    /**
     * The tool change a call is offering, or null if it is not offering one.
     *
     * Read by the caller as well as run, for the reason [suggestionIn] is: the
     * offer has to reach the screen somebody is looking at, and only the door
     * they came through knows where that is.
     */
    fun toolSuggestionIn(scope: OrknuxScope, arguments: String): ToolSuggestion? {
        val code = text(arguments, "code")?.takeIf { it.isNotBlank() } ?: return null
        val chosen = toolCalled(scope, text(arguments, "tool")) ?: return null

        return ToolSuggestion(
            toolId = requireNotNull(chosen.id),
            name = chosen.name,
            note = text(arguments, "note"),
            code = code,
        )
    }

    private fun runWorkflow(scope: OrknuxScope, arguments: String): String {
        if (!scope.mayWrite) return refuse("This conversation may read what workflows did, but not start one")

        val asked = text(arguments, "workflow") ?: return refuse("Which workflow? Give its name or id.")
        val held = assignments.findByWorkspaceId(scope.workspaceId, PageRequest.of(0, MANY, Sort.by("workflow.name")))
            .content

        /*
         * By name or by id, and a name that matches two is refused rather than
         * guessed at. Starting the wrong workflow is not an error anybody can
         * take back.
         */
        val matches = held.filter { assignment ->
            assignment.workflow.name.equals(asked, ignoreCase = true) || assignment.workflow.id?.toString() == asked
        }
        val chosen = when {
            matches.isEmpty() -> return refuse("There is no workflow called $asked here")
            matches.size > 1 -> return refuse("More than one workflow is called $asked; use its id")
            else -> matches.single()
        }

        /*
         * The switch means a workflow does not start by itself, and a tool call
         * is by itself: nobody is looking at the workflow when this arrives.
         * Somebody who wants it running again can say so with
         * orknux_set_workflow_enabled, which is why the refusal names it.
         */
        if (!chosen.enabled) {
            return refuse(
                "${chosen.workflow.name} is switched off in this workspace, so it is not started by a tool call. " +
                    "Turn it back on with orknux_set_workflow_enabled first.",
            )
        }

        val started = runs.startExecution(
            StartExecutionInput(
                workspaceId = scope.workspaceId,
                workflowId = requireNotNull(chosen.workflow.id),
                trigger = ExecutionTrigger.API,
                payload = text(arguments, "input"),
            ),
        )
        return mapper.writeValueAsString(
            mapOf(
                "started" to started.id,
                "workflow" to started.workflowName,
                "status" to started.status,
                "url" to runLink(scope.workspaceId, started.id),
                "note" to "Ask orknux_execution about this id to see where it got to.",
            ),
        )
    }

    /** Runs a past run again, on the input it was given. */
    private fun rerun(scope: OrknuxScope, arguments: String): String {
        if (!scope.mayWrite) return readOnly()

        val id = number(arguments, "id") ?: return refuse("Which run? Give its id.")
        val original = runs.execution(id)?.takeIf { it.workspaceId == scope.workspaceId }
            ?: return refuse("There is no run $id here")

        // Off for the same reason it is off above: repeating a run is still a
        // start, and this one is asked for by a tool rather than by a person.
        val assignment = assignments.findByWorkspaceIdAndWorkflowId(scope.workspaceId, original.workflowId)
        if (assignment != null && !assignment.enabled) {
            return refuse("${original.workflowName} is switched off in this workspace, so its runs are not repeated")
        }

        val started = runs.startExecution(
            StartExecutionInput(
                workspaceId = scope.workspaceId,
                workflowId = original.workflowId,
                trigger = ExecutionTrigger.API,
                // The original input, so it acts on the same event rather than
                // on nothing — which is what makes this a repeat at all.
                payload = original.input,
            ),
        )
        return mapper.writeValueAsString(
            mapOf(
                "started" to started.id,
                "workflow" to started.workflowName,
                "repeated" to id,
                "url" to runLink(scope.workspaceId, started.id),
            ),
        )
    }

    /*
     * Saved rather than left to dirty checking, and deliberately not
     * `@Transactional`.
     *
     * These are reached from `run` on this same object, so a proxy-based
     * annotation would not apply to them at all — the call never leaves the
     * bean. That failed quietly in exactly the worst way: no transaction, no
     * flush, and a model reporting that it had turned a workflow off while the
     * row was untouched. An explicit save is what actually happens here.
     */
    private fun setWorkflowEnabled(scope: OrknuxScope, arguments: String): String {
        if (!scope.mayWrite) return readOnly()

        val asked = text(arguments, "workflow") ?: return refuse("Which workflow? Give its name or id.")
        val wanted = flag(arguments, "enabled") ?: return refuse("On or off? Say enabled true or false.")

        val matches = assignments
            .findByWorkspaceId(scope.workspaceId, PageRequest.of(0, MANY, Sort.by("workflow.name")))
            .content
            .filter { it.workflow.name.equals(asked, ignoreCase = true) || it.workflow.id?.toString() == asked }
        val chosen = when {
            matches.isEmpty() -> return refuse("There is no workflow called $asked here")
            matches.size > 1 -> return refuse("More than one workflow is called $asked; use its id")
            else -> matches.single()
        }

        chosen.enabled = wanted
        assignments.save(chosen)
        return mapper.writeValueAsString(
            mapOf(
                "workflow" to chosen.workflow.name,
                "enabled" to wanted,
                "url" to workflowLink(scope.workspaceId, chosen.workflow.id),
            ),
        )
    }

    private fun setAgentEnabled(scope: OrknuxScope, arguments: String): String {
        if (!scope.mayWrite) return readOnly()

        val asked = text(arguments, "agent") ?: return refuse("Which agent? Give its name or id.")
        val wanted = flag(arguments, "enabled") ?: return refuse("On or off? Say enabled true or false.")

        val chosen = agents.findByWorkspaceIdAndName(scope.workspaceId, asked)
            ?: agents.findByWorkspaceId(scope.workspaceId, PageRequest.of(0, MANY, Sort.by("name")))
                .content
                .firstOrNull { it.id?.toString() == asked }
            ?: return refuse("There is no agent called $asked here")

        /*
         * The same recording the browser's toggle makes.
         *
         * This is the door the tracker's history was missing for a year: the
         * MCP tools wrote and nothing wrote it down, so the gaps in the record
         * lined up exactly with the work nobody was watching. An agent switched
         * off from here is the same thing happening to that agent.
         */
        revisions.saved(chosen)
        chosen.enabled = wanted
        chosen.lastModifiedAt = OffsetDateTime.now()
        chosen.lastModifiedBy = currentUser(scope)
        agents.save(chosen)
        return mapper.writeValueAsString(
            mapOf("agent" to chosen.name, "enabled" to wanted, "url" to agentLink(scope.workspaceId, chosen.id)),
        )
    }

    /**
     * Whoever is asking, for the stamp a revision of this state will carry.
     *
     * A signed-in name wins; an agent reaching these in-process has none and is
     * named by [OrknuxScope.actor] instead. See `IssueTools.currentUser` for why
     * that order and not the other one.
     */
    private fun currentUser(scope: OrknuxScope): String =
        SecurityContextHolder.getContext().authentication?.name ?: scope.actor ?: "orknux"

    private fun readOnly(): String = refuse("This conversation may read what is here, but not change it")

    private fun refuse(says: String): String = mapper.writeValueAsString(mapOf("error" to says))

    /** Arguments arrive as a JSON object in a string, whichever shape asked. */
    private fun text(arguments: String, name: String): String? = runCatching {
        mapper.readTree(arguments).path(name).stringValue()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** The same, for a boolean a model may well have sent as "true". */
    private fun flag(arguments: String, name: String): Boolean? = runCatching {
        val node = mapper.readTree(arguments).path(name)
        when {
            node.isBoolean -> node.booleanValue()
            node.isString -> node.stringValue()?.trim()?.lowercase()?.toBooleanStrictOrNull()
            else -> null
        }
    }.getOrNull()

    /** The same, for a number a model may well have sent as a string. */
    private fun number(arguments: String, name: String): Long? = runCatching {
        val node = mapper.readTree(arguments).path(name)
        if (node.isNumber) node.asLong() else node.stringValue()?.trim()?.toLongOrNull()
    }.getOrNull()

    private companion object {
        const val PREFIX = "orknux_"

        /** One page, big enough that a workspace's whole catalogue fits in it. */
        const val MANY = 100
        const val DEFAULT_RUNS = 20

        /** A step's output can be a megabyte; a model reading it needs the shape. */
        const val FIELD = 2_000
    }
}
