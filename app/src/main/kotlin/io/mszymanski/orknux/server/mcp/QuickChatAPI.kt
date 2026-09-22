package io.mszymanski.orknux.server.mcp

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.ModelChatClient
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * The chat that opens beside whatever somebody is looking at.
 *
 * Not the Chat page in miniature: it carries no history of its own — the panel
 * sends the conversation up each time — and belongs to no workspace
 * conversation. What it has instead is where the person is, the page they have
 * open, and orknux's own tools, so "why did last night's sync fail" can be
 * answered by going and looking rather than by asking them to fetch the run
 * themselves.
 *
 * Whether it may do anything, or only look things up, is the workspace's to
 * decide. It reads by default: the panel opens over whatever somebody is
 * reading, and a model that decides "run it" from a question is a worse mistake
 * there than on a page with a button on it.
 *
 * What it does is written down, into an LLM session like every other loop that
 * calls tools. It used to be written down nowhere at all: a panel that could be
 * given permission to start a run left no account of having started one, which
 * is the wrong way round for the one loop here with a switch on its authority.
 * Recording through [LlmSessionRecorder] rather than into something of its own
 * is what makes the redaction the same redaction - the recorder redacts on the
 * way in, so this cannot drift into a second answer about what a credential
 * looks like.
 *
 * Written to and never read back. The panel's history comes up with the
 * request, so reading the session in would hand the model yesterday's panel as
 * well as today's question; what the session is for here is somebody reading it
 * afterwards.
 */
@Service
class QuickChat(
    private val models: ModelChatClient,
    private val orknux: OrknuxTools,
    private val sessions: LlmSessionRecorder,
    /** The installation's ceiling on tool rounds, which the panel follows too. */
    private val settings: InstallationSettings,
) {

    /**
     * What one round of the panel produced: the answer, and anything it is
     * offering to change.
     *
     * The suggestion travels beside the answer rather than inside it, because
     * it is not prose: the panel draws it against what the function says now
     * and puts an accept and a reject under it.
     */
    data class Answer(
        val completion: ChatCompletion,
        val suggestion: FunctionSuggestion? = null,
        /**
         * The same offer made about a tool, carried in its own field.
         *
         * Two fields rather than one of either kind, because the two land in
         * different editors and the panel has to know which before it announces
         * anything. A round can only produce one of them in practice — a model
         * that offered both has changed its mind mid-turn — but nothing here
         * needs to enforce that.
         */
        val toolSuggestion: ToolSuggestion? = null,
    )

    /**
     * @param asker who is at the panel, which names the session and stands
     *   against every line this round writes into it. A transcript of tool
     *   calls with nobody attached answers "what happened" and not "who", and
     *   the second question is the one asked about a panel that may write.
     */
    fun answer(
        modelId: Long,
        workspaceId: Long,
        mayWrite: Boolean,
        page: PageContext?,
        said: List<ChatTurn>,
        asker: String,
    ): Answer {
        /*
         * Somebody is at a screen here, which is what makes offering a change
         * worth anything - and they may only be offered one where this panel is
         * allowed to change things, since accepting it saves the function.
         */
        val scope = OrknuxScope(workspaceId = workspaceId, mayWrite = mayWrite, watched = mayWrite)
        var offering: FunctionSuggestion? = null
        var offeringTool: ToolSuggestion? = null
        val offered = orknux.specs(scope)
        val conversation = mutableListOf(ChatTurn(role = "system", content = briefing(page, mayWrite)))
        conversation += said

        /*
         * One session per person per workspace, so what somebody's panel has
         * been doing reads as one thing rather than as a session per question.
         *
         * A transcript that cannot be written is not a reason to refuse to
         * answer - the recorder itself takes that view line by line, and a
         * session that cannot be opened at all is the same judgement one level
         * up. What it costs is a round nobody can read afterwards, which is
         * what this was before.
         */
        val session = runCatching { sessions.open(workspaceId, SESSION_PREFIX, asker) }
            .onFailure { log.warn("Quick chat could not open a session for {}", asker, it) }
            .getOrNull()

        // The question, before the model is asked it: a round that dies waiting
        // on a provider should still leave what was asked.
        session?.let { held -> said.lastOrNull { it.role == "user" }?.let { sessions.userSaid(held, asker, it.content) } }

        var spent = 0L
        var calls = 0
        /*
         * The installation's number. The panel has no agent to carry one of its
         * own, so it follows the setting - which is the point of the setting:
         * somebody whose models look things up one at a time raises it once.
         */
        val rounds = settings.chatMaxRounds()
        repeat(rounds) { round ->
            /*
             * The last round is asked without tools, so it has to answer.
             *
             * A model looking things up one at a time can spend every round on
             * that and never say anything - and what came back was a refusal
             * about the panel rather than an answer, after it had read
             * everything it needed. Taking the tools away on the last round
             * turns "I ran out of looking" into "here is what I found".
             */
            val last = round == rounds - 1
            when (val answer = models.complete(modelId, conversation, if (last) emptyList() else offered)) {
                is ChatCompletion.Failed -> {
                    // Written down as well, because a panel that answered
                    // nothing is a thing somebody comes asking about.
                    session?.let { sessions.note(it, "The model could not answer: ${answer.reason}") }
                    return Answer(answer)
                }

                is ChatCompletion.Answered -> {
                    session?.let { sessions.agentSaid(it, QUICK_CHAT, answer.content) }
                    return Answer(answer.copy(millis = spent + answer.millis), offering, offeringTool)
                }
                is ChatCompletion.CalledTools -> {
                    spent += answer.millis
                    calls += answer.calls.size
                    conversation += answer.turn
                    answer.calls.forEach { call ->
                        log.debug("Quick chat called {}", call.name)
                        /*
                         * Kept as it goes past. The last one wins: a model that
                         * offers two rewrites in one turn has changed its mind,
                         * and showing both would ask somebody to choose between
                         * versions nobody described.
                         */
                        if (call.name == "orknux_suggest_function_code") {
                            orknux.suggestionIn(scope, call.arguments)?.let { offering = it }
                        }
                        if (call.name == "orknux_suggest_tool_code") {
                            orknux.toolSuggestionIn(scope, call.arguments)?.let { offeringTool = it }
                        }

                        /*
                         * Recorded before it is run, and answered onto the same
                         * line afterwards, exactly as an agent's round is. The
                         * order is the point: a call that never came back is
                         * the one somebody is looking for, and a line written
                         * only on success would be missing precisely then.
                         *
                         * The recorder redacts what it is handed, so nothing
                         * here has to - and nothing here should, since a second
                         * redaction is a second answer about what a credential
                         * looks like.
                         */
                        val line = session?.let { sessions.toolCalled(it, call.name, call.arguments) }
                        val got = orknux.run(scope, call.name, call.arguments)
                        sessions.toolReturned(line, got)

                        conversation += ChatTurn(
                            role = "user",
                            content = got,
                            respondingTo = call.id,
                        )
                    }
                }
            }
        }

        /*
         * Only reachable by a model that asked for a tool when it was offered
         * none, which is a provider not honouring the request rather than a
         * conversation that went on too long.
         */
        log.warn("Quick chat asked for tools on its last round after {} calls", calls)
        session?.let { sessions.note(it, "The model asked for a tool when it was offered none, after $calls calls.") }
        return Answer(ChatCompletion.Failed("That could not be answered here. Try the Chat page."))
    }

    /**
     * What the model is told before anything else.
     *
     * The page matters more than it looks. Somebody who opens this while
     * standing on a failed run means *that* run, and a panel that has to ask
     * "which run?" is slower than the page they are already looking at.
     */
    private fun briefing(page: PageContext?, mayWrite: Boolean): String = buildString {
        append(
            "You are the quick assistant inside orknux, a workflow and agent platform. " +
                "Answer in one or two sentences unless asked for more. " +
                "You can look things up with the orknux_ tools; prefer looking to guessing, " +
                "and say plainly when something is not there. ",
        )
        /*
         * Told to use the links the tools give it.
         *
         * Every run, workflow and agent comes back with a `url`, and an answer
         * that names one without linking to it leaves somebody to go and find
         * it — which is the whole thing this panel was meant to save.
         */
        append(
            "Whenever you mention a run, a workflow, an agent or a function, link to it using the `url` " +
                "the tool gave you, as a markdown link like [run 20](url). Never invent a link. ",
        )
        /*
         * The code, and what may be done with it.
         *
         * Reading a function before discussing it is the difference between
         * helping with the code and describing what a function of that name
         * might contain. Saying that a suggestion cannot be saved is the honest
         * half: nothing here writes a function, because what runs is compiled
         * from TypeScript by the editor in the browser and there is no compiler
         * on this side to keep the two halves the same.
         */
        append(
            "When a question is about a function's code, read it with `orknux_function` before answering " +
                "rather than describing what it might contain. ",
        )
        /*
         * A tool is not a function, said before anything makes that mistake.
         *
         * It was made: somebody asked for help on `/workspace/1/tools/15` and
         * was told there is no function with id 15, followed by a list of the
         * functions - a refusal about the wrong kind of thing, from a panel
         * that had been handed the address of the right one. Both halves are
         * needed here, the word and where it lives, because the id in the path
         * is the only thing distinguishing the two and it is not distinguishing
         * on its own.
         */
        append(
            "A tool is a different thing from a function: a tool is TypeScript an agent calls while it runs, " +
                "it lives at `/workspace/<workspace>/tools/<tool>`, and it is read with `orknux_tool` — never " +
                "with `orknux_function`. Ids are not shared between them, so on a tools page use the tool tools. ",
        )
        /*
         * How to offer a change, and what happens to it.
         *
         * Said plainly because the alternative is a model that pastes a whole
         * function into the conversation and asks somebody to copy it - which
         * is what this replaced. The tool puts the change beside what is there
         * now, with an accept and a reject; the next thing in the conversation
         * is which of those they chose, and it is a fact rather than a guess.
         */
        append(
            if (mayWrite) {
                "To change one, call `orknux_suggest_function_code` with the complete new source: they are shown " +
                    "it against what is there now and either accept it or reject it. Do not paste a whole " +
                    "function into your reply and ask them to copy it - offer it with the tool and say in one " +
                    "line what it changes. Nothing is saved unless they accept. " +
                    /*
                     * The sandbox, said up front. A model that does not know it
                     * writes Node - `import crypto` was the first thing one
                     * tried - and then spends three suggestions discovering,
                     * one refusal at a time, what one sentence here prevents.
                     */
                    "Functions run in a locked-down sandbox: no `import` or `require` at all, no Node or browser " +
                    "APIs (no `crypto`, `fs`, `fetch`, `process`), no network. Only plain TypeScript over the " +
                    "declared parameters and standard JavaScript built-ins. If something needs a capability the " +
                    "sandbox lacks, say so instead of trying to smuggle it in. " +
                    /*
                     * The signature is the declaration, and nothing else.
                     *
                     * Without this a model asked for a parameter wrote one into
                     * the code and had the accept refused, because the function
                     * went on declaring the list it had before. It is one tool
                     * and one list now: what the declaration takes is what the
                     * function takes, so the two cannot come apart.
                     */
                    /*
                     * The fields of a named shape, and where to find them.
                     *
                     * Annotating a parameter with an object's name is only half
                     * of writing against it: the body has to read its fields,
                     * and a model that has been told the name and not the fields
                     * makes them up. They come back from `orknux_function`, with
                     * whatever the author wrote about what each one means.
                     */
                    "When a parameter or the return type names one of this workspace's objects, `orknux_function` " +
                    "sends that object's fields with it - each field's type and what its author says it means. " +
                    "Write the body against those fields rather than inventing any. " +
                    "A function's parameters are the ones its declaration lists, so you can add, remove, rename " +
                    "or retype one by writing the declaration you want and offering it with the same tool - the " +
                    "parameter list is taken from it when they accept. Annotate every parameter: `string`, " +
                    "`number`, `boolean`, `Record<string, unknown>`, `unknown[]`, or the name of one of this " +
                    "workspace's objects. Any workspace variables the function is handed come after its own " +
                    "parameters and must stay last, in the same order and under the same names. " +
                    /*
                     * One offer at a time, and honest words when it lands. The
                     * transcript this guards against had three suggestions in
                     * flight, a model narrating a wait that was over, and a
                     * placeholder described as the finished algorithm after it
                     * was accepted.
                     */
                    "Offer one change at a time and wait for the outcome - accepted, rejected, or failed - before " +
                    "offering another. When one is accepted, describe what the accepted code actually does, no " +
                    "more; never present a placeholder or a partial version as the finished thing. "
            } else {
                "You cannot change a function here; describe what you would do instead. "
            },
        )
        /*
         * The same offer for a tool.
         *
         * A tool declares parameters now, as a function does, and the editor
         * reads them back off whatever is offered — so what the declaration
         * takes is what the tool takes, and the two cannot come apart. The
         * sandbox is the same one, and saying so once here saves the same
         * three refusals it saves for functions.
         */
        append(
            if (mayWrite) {
                "To change a tool, call `orknux_suggest_tool_code` with the complete new TypeScript: they are " +
                    "shown it against what is there now and either accept it or reject it, and nothing is " +
                    "saved unless they accept. Do not paste a tool into your reply and ask them to copy it. " +
                    "A tool is a default export whose parameters are what an agent calling it fills in, so " +
                    "give it the parameters it needs and annotate every one of them with its type. It runs in the same " +
                    "locked-down sandbox as a function: no `import` or `require`, no Node or browser APIs, no " +
                    "network. Its description is what an agent reads to decide whether to call it, so say when " +
                    "a change makes that description wrong. "
            } else {
                "You cannot change a tool here; describe what you would do instead. "
            },
        )
        /*
         * What the sandbox *does* give them, after the long list of what it does
         * not.
         *
         * A model that knows only the prohibitions still has to guess at the one
         * thing that is there, and it guessed wrong: asked how to count the
         * messages in a Slack thread it said to take `messages.length`, which is
         * the length of the page that was fetched and counts the parent, when
         * the answer is `replies` — Slack's own count of the whole thread. The
         * editor has known this shape all along; the panel beside it did not.
         *
         * The declarations rather than a description of them, because the shape
         * is the answer: `messages` and `replies` are both there and picking
         * between them is the whole question.
         */
        append(HOST_CALLS)
        /*
         * Said as well as enforced. The scope already withholds the tool, so a
         * model told nothing would offer to start a workflow and then fail —
         * and where it may, being told saves it from refusing out of caution.
         */
        append(
            if (mayWrite) {
                "You may also change things when asked to — starting or repeating a run, turning a workflow " +
                    "or an agent on or off. These are real: say what you did. You cannot delete anything."
            } else {
                "You cannot change anything: you may look, and nothing else."
            },
        )
        if (page != null) {
            append("\n\nThe person is looking at ")
            append(page.label?.takeIf { it.isNotBlank() } ?: "a page")
            page.path?.takeIf { it.isNotBlank() }?.let { append(" ($it)") }
            append(
                ". Take \"this\", \"here\" and \"it\" to mean whatever that page is showing, " +
                    "and use the ids in the path when they help.",
            )
        }
    }

    private companion object {
        /**
         * What the panel's sessions are filed under, so they sort together and
         * are obviously not somebody's workflow conversation.
         */
        const val SESSION_PREFIX = "quick-chat"

        /** The name every line this loop writes stands under. */
        const val QUICK_CHAT = "Quick chat"

        /**
         * The one thing the sandbox provides, as the editor declares it.
         *
         * A second copy of what `orknux-ui/src/components/monaco.ts` feeds the
         * editor, and the shapes have to say the same thing: a panel that
         * described a different `orknux` from the one autocompleting under it
         * would be worse than the panel that described none. Kept to the shapes
         * and the signature - the prose around them in that file is for somebody
         * reading it, and this is for a model that has two sentences of answer
         * to give.
         *
         * `QuickChatBriefingTest` pins what a change here must not lose.
         */
        val HOST_CALLS =
            "\n\nThe sandbox provides exactly one thing beyond the language, and this is its whole surface:\n" +
                """
                type OrknuxConnectionRef = SlackConnection | number | string;

                type SlackThreadMessage = {
                  readonly ts: string;      // Slack's timestamp, and the message's id
                  readonly user: string | null;  // null where Slack named nobody
                  readonly text: string;
                  readonly parent: boolean; // the message the thread hangs off, not a reply
                };

                type SlackThread =
                  | { messages: SlackThreadMessage[]; replies: number; error?: undefined }
                  | { error: string; messages?: undefined; replies?: undefined };

                type SlackPost =
                  | { channel: string; ts: string | null; error?: undefined }
                  | { error: string; channel?: undefined; ts?: undefined };

                type SlackReaction =
                  | { ok: true; error?: undefined }
                  | { error: string; ok?: undefined };

                type SlackLinkedMessage =
                  | { channel: string; ts: string; user: string | null; text: string; threadTs: string | null; error?: undefined }
                  | { error: string; text?: undefined };

                type SlackUserInfo =
                  | { id: string; name: string; realName: string | null; displayName: string | null; bot: boolean; error?: undefined }
                  | { error: string; id?: undefined };

                type SlackMention =
                  | { mention: string; id: string; label: string; error?: undefined }
                  | { error: string; mention?: undefined };

                type OrknuxResponse =
                  | { status: number; headers: Record<string, string>; body: string; json?: unknown; error?: undefined }
                  | { error: string };

                declare const orknux: {
                  readonly slack: {
                    thread(
                      connection: OrknuxConnectionRef,
                      channel: string,
                      threadTs: string,
                      limit?: number,
                    ): SlackThread;
                    post(
                      connection: OrknuxConnectionRef,
                      channel: string,
                      text: string,
                      threadTs?: string,
                    ): SlackPost;
                    react(
                      connection: OrknuxConnectionRef,
                      channel: string,
                      ts: string,
                      emoji: string,
                    ): SlackReaction;
                    message(
                      connection: OrknuxConnectionRef,
                      link: string,
                    ): SlackLinkedMessage;
                    user(
                      connection: OrknuxConnectionRef,
                      userId: string,
                    ): SlackUserInfo;
                    mention(
                      connection: OrknuxConnectionRef,
                      name: string,
                    ): SlackMention;
                  };
                  readonly log: {
                    debug(...said: unknown[]): void;
                    info(...said: unknown[]): void;
                    warn(...said: unknown[]): void;
                    error(...said: unknown[]): void;
                  };
                  readonly http: {
                    request(what: string | { url: string; method?: string; headers?: Record<string, string>; body?: unknown }): OrknuxResponse;
                    get(url: string, headers?: Record<string, string>): OrknuxResponse;
                    post(url: string, body?: unknown, headers?: Record<string, string>): OrknuxResponse;
                  };
                };
                """.trimIndent() +
                "\nRead `error` before `messages`: a refusal is data, not a thrown error. " +
                "`messages` is the page that was fetched and includes the parent; `replies` is Slack's own " +
                "count of the whole thread, so it is the number to use for \"how many replies\" and " +
                "`replies === 1` is the first one. " +
                "`slack.post` sends a message and `slack.react` adds an emoji, both through a connection the " +
                "workspace was given, the same as `thread`; `post` answers with the new message's `ts`, which " +
                "`react` then hangs on. Read `error` first on each. " +
                "`slack.message` follows a permalink - the address a message pasted into another message travels " +
                "as - to the one message it points at. `slack.user` says who a `<@U…>` mention is; hand it the id " +
                "or the whole notation. `slack.mention` turns a name, a user group handle or an email into the " +
                "notation to put in `slack.post`'s text - `<@U…>` for a person, `<!subteam^S…>` for a group; never " +
                "write those by hand from a guessed id. " +
                "`orknux.http` is the only way out to a network: the server makes the request and hands back " +
                "data, so there is no `fetch` and no socket. An object body is sent as JSON and given a JSON " +
                "content type; a JSON reply arrives parsed as `json`, beside the `body` it was parsed from. " +
                "Read `error` first there too. A credential belongs in a workspace variable, which arrives as a " +
                "parameter after the function's own - never written into the source. " +
                "`orknux.log` is how a function says anything: there is no `console`, the level is the " +
                "installation's, and anything that is not a string is logged as JSON. " +
                "There is nothing else on `orknux`. "

        val log = LoggerFactory.getLogger(QuickChat::class.java)
    }
}

/**
 * Answers the panel.
 *
 * REST rather than GraphQL to sit beside the other two things a chat needs —
 * transcription and speech — and because what goes up is a short conversation
 * that is never stored, which is a poor fit for a mutation that implies it was.
 */
@RestController
class QuickChatAPI(
    private val workspaces: WorkspaceRepository,
    private val quickChat: QuickChat,
    private val access: WorkspaceAccess,
) {

    @PostMapping("/api/workspaces/{workspaceId}/quick-chat")
    fun ask(
        @PathVariable workspaceId: Long,
        @RequestBody asked: QuickChatRequest,
    ): ResponseEntity<Any> {
        val workspace = access.requireVisible(workspaceId)

        val modelId = workspace.quickChatModelId
            ?: return refuse(HttpStatus.CONFLICT, "This workspace has no quick chat model.")

        val turns = asked.messages
            .filter { it.content.isNotBlank() }
            .map { ChatTurn(role = if (it.role == "assistant") "assistant" else "user", content = it.content) }
        if (turns.isEmpty()) return refuse(HttpStatus.BAD_REQUEST, "There is nothing to answer.")

        val said = quickChat.answer(
            modelId,
            workspaceId,
            workspace.quickChatMayWrite,
            asked.page,
            turns,
            // Who is at the panel. Resolved here rather than deeper down: this
            // is the layer that has a request and therefore a person, and a
            // service reaching into the security context to find out who it is
            // working for is a service that cannot be called from anywhere else.
            asker = SecurityContextHolder.getContext().authentication?.name ?: "somebody",
        )
        return when (val answer = said.completion) {
            is ChatCompletion.Answered -> ResponseEntity.ok(
                buildMap {
                    put("answer", answer.content)
                    put("millis", answer.millis)
                    // Only when there is one: an absent field is easier for the
                    // panel to read than a null it has to keep testing.
                    said.suggestion?.let {
                        put(
                            "suggestion",
                            mapOf(
                                "functionId" to it.functionId.toString(),
                                "function" to it.name,
                                "note" to it.note,
                                "code" to it.code,
                            ),
                        )
                    }
                    said.toolSuggestion?.let {
                        put(
                            "toolSuggestion",
                            mapOf(
                                "toolId" to it.toolId.toString(),
                                "tool" to it.name,
                                "note" to it.note,
                                "code" to it.code,
                            ),
                        )
                    }
                },
            )
            is ChatCompletion.Failed -> refuse(HttpStatus.BAD_GATEWAY, answer.reason)
            // The loop above ends on one of the two above; a tool call reaching
            // here would mean it did not, and saying so beats answering blank.
            is ChatCompletion.CalledTools -> refuse(HttpStatus.BAD_GATEWAY, "The model asked for a tool and stopped.")
        }
    }

    private fun refuse(status: HttpStatus, says: String): ResponseEntity<Any> =
        ResponseEntity.status(status).body(mapOf("error" to says))
}

/** Where the person is, as the interface knows it. */
data class PageContext @JsonCreator constructor(
    @JsonProperty("label") val label: String?,
    @JsonProperty("path") val path: String?,
)

data class QuickChatMessage @JsonCreator constructor(
    @JsonProperty("role") val role: String,
    @JsonProperty("content") val content: String,
)

data class QuickChatRequest @JsonCreator constructor(
    @JsonProperty("messages") val messages: List<QuickChatMessage>,
    @JsonProperty("page") val page: PageContext?,
)
