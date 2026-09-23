package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.action.FunctionParam
import io.mszymanski.orknux.server.action.ValueType
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.McpToolCaller
import io.mszymanski.orknux.server.agent.SkillTool
import io.mszymanski.orknux.server.agent.PluginToolCaller
import io.mszymanski.orknux.server.agent.WorkspaceToolCaller
import io.mszymanski.orknux.server.mcp.OrknuxScope
import io.mszymanski.orknux.server.mcp.OrknuxTools
import io.mszymanski.orknux.server.memory.MemoryTool
import io.mszymanski.orknux.server.shell.ShellTools
import io.mszymanski.orknux.server.workflow.SavedArtifacts
import io.mszymanski.orknux.workflow.script.PluginCrypto
import io.mszymanski.orknux.server.memory.ToolDescriptor
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/**
 * What an agent may call, and what happens when it does.
 *
 * One place that knows every built-in, so the model is offered exactly what will
 * run: a tool declared but not implemented is a model told it can do something
 * it cannot, and it will believe you.
 *
 * What is offered depends on the agent. Memory appears only for an agent granted
 * catalogs, and skills only for an agent granted those — an agent given nothing
 * is handed no tools at all rather than tools that answer "nothing here", which
 * is a round trip spent to learn what the grant already said.
 */
@Service
class AgentTools(
    /** Where this installation is, for the one answer that carries a link. */
    private val web: io.mszymanski.orknux.server.security.WebProperties,
    private val skills: SkillTool,
    private val memories: MemoryTool,
    private val workspaceTools: WorkspaceToolCaller,
    private val pluginTools: PluginToolCaller,
    private val mcpTools: McpToolCaller,
    private val orknux: OrknuxTools,
    private val shells: ShellTools,
    /** What lets an agent find a connection id it was granted; see [ConnectionTools]. */
    private val connectionTools: ConnectionTools,
    /** What lets an agent put a question to another; see [AgentRunTools]. */
    private val agentTools: AgentRunTools,
    private val savedArtifacts: SavedArtifacts,
    private val mapper: ObjectMapper,
) {

    /**
     * What an agent may reach of orknux: its own workspace, and no wider.
     *
     * Writing is allowed because starting a workflow is most of what an agent
     * granted this is for. The grant is the decision; there is no session here
     * to ask for a second one.
     */
    private fun scopeFor(agent: Agent) = OrknuxScope(
        workspaceId = agent.workspaceId,
        mayWrite = true,
        /*
         * And it signs its own name to what it does. There is no session behind
         * an agent's tool call, so everything it wrote on an issue was filed
         * under the product's name and a tracker could not say which agent had
         * done the work (issue #230). The agent has a name somebody chose; it
         * is the true answer to who commented.
         */
        actor = agent.name,
    )

    /**
     * Everything the agent holds, which is what a round declares while it fits.
     *
     * Kept as it was: every caller that does not care about the count reads
     * this, and below the provider's ceiling nothing about a round changes.
     */
    fun specsFor(agent: Agent): List<ToolSpec> = offeringFor(agent).let { it.core + it.searchable }

    /**
     * The same tools, split by whether they travel on every call.
     *
     * An agent granted more tools than the provider will accept could not
     * answer at all: OpenAI and Azure refuse the whole request over 128 of
     * them, and what reached the person who asked was the provider's own
     * sentence about an array being too long. Raising the cap is not available;
     * not sending all of them is.
     *
     * [core] is what an agent uses constantly and what there are few of: the
     * skills and memories it was granted, orknux itself, a shell, somewhere to
     * put what it made. [searchable] is everything that scales - the workspace's
     * own tools, what the granted MCP servers offer, the plugins' - which is
     * where three hundred tools come from and which an agent looks through
     * rather than carries.
     *
     * The split is stated rather than taken from the order they were built in,
     * so a tool added to the wrong half is a decision somebody made rather than
     * a line that landed in the wrong place.
     */
    fun offeringFor(agent: Agent): Offering {
        val core = coreFor(agent)
        val searchable = searchableFor(agent)

        /*
         * The ones somebody said always travel, moved across. Issue #372.
         *
         * A tool an agent uses constantly should not have to be found: an agent
         * spending a round rediscovering the one thing it does every time pays
         * the cost of the search without the benefit. Marking one puts it beside
         * the built-ins, which is exactly what "required" means here.
         *
         * Only where the agent carries a ceiling of its own. Without one nothing
         * is ever dropped, so marking a tool would be marking it against a thing
         * that never happens - and the screen does not offer the column either.
         */
        if (agent.maxTools == null || agent.requiredTools.isEmpty()) {
            return Offering(core = core, searchable = searchable)
        }

        val always = agent.requiredTools.toSet()
        return Offering(
            core = core + searchable.filter { it.name in always },
            searchable = searchable.filterNot { it.name in always },
        )
    }

    private fun coreFor(agent: Agent): List<ToolSpec> = buildList {
        if (agent.skillCatalogs.isNotEmpty()) addAll(skills.descriptors().map(::spec))
        if (agent.memoryCatalogs.isNotEmpty()) {
            add(spec(memories.descriptor()))
            // The writing half of the same grant: an agent given a catalog can
            // add to what it holds, so a lesson outlives the conversation.
            add(spec(memories.saveDescriptor()))
        }

        // orknux itself, for an agent granted it. Scoped to the agent's own
        // workspace: the grant is the authorisation, and the workspace is the
        // boundary — there is no session here to ask about anything wider.
        if (agent.orknuxAccess) addAll(orknux.specs(scopeFor(agent)))

        // The machines, for an agent granted them. Unnamed and plural, which is
        // the design: an agent asks for a shell, not for a particular host, and
        // which one it gets is decided when the session opens.
        if (agent.shellAccess) addAll(shells.specs())

        /*
         * And the ids of the connections it was granted, where there are
         * enough of them to be worth asking for.
         *
         * Core rather than searchable: it is one tool, it is how an agent gets
         * an id at all, and an agent that had to find the finder would be a
         * round worse off than one that was simply told. A handful of grants is
         * still recited in the briefing, which is cheaper than a round trip -
         * see [ConnectionTools].
         */
        if (connectionTools.offered(agent)) add(connectionTools.specFor(agent))

        /*
         * And the other agents it may ask, where it was granted any.
         *
         * Core rather than searchable: it is one tool, what it can reach is
         * named in its own description, and an agent that had to find the way to
         * delegate is a round worse off than one that was told.
         */
        if (agentTools.offered(agent)) add(agentTools.specFor(agent))

        /*
         * Somewhere to put what it made, and the one conversion getting it
         * there needs.
         *
         * Offered to every agent rather than behind a grant: the other grants
         * open a door onto something that already exists - the workspace, a
         * machine, a catalog - and these only let an agent keep its own output
         * where somebody can find it. The bounds that matter are on the saving
         * (a size, a count per workspace) rather than on who may ask.
         *
         * Left out where attachments are off, because then there is nowhere to
         * file bytes and offering them spends a turn teaching the model that.
         */
        if (agent.artifactAccess && savedArtifacts.offered()) addAll(ARTIFACT_TOOLS)

    }

    /**
     * The half an agent searches rather than carries.
     *
     * Named against what [coreFor] already claimed, because the shadow rule is
     * the same either way round: two tools answering to one name is a call
     * nobody can predict the destination of, and a tool named like a built-in
     * is skipped rather than allowed to shadow it.
     */
    private fun searchableFor(agent: Agent): List<ToolSpec> = buildList {
        val builtIn = coreFor(agent).map { it.name }.toMutableSet()

        // The workspace's own code, under its own names. A tool named like a
        // built-in is skipped rather than shadowing it.
        // What the granted MCP servers say they offer, asked of them now.
        addAll(mcpTools.specsFor(agent).filterNot { it.name in builtIn })
        builtIn += map { it.name }

        workspaceTools.granted(agent)
            .filterNot { tool ->
                (tool.name in builtIn).also { clash ->
                    if (clash) log.warn("Tool {} is named like a built-in and was not offered", tool.name)
                }
            }
            .forEach { tool ->
                add(
                    ToolSpec(
                        name = tool.name,
                        description = tool.description ?: "One of this workspace's tools.",
                        /*
                         * What the tool says it takes. Every tool used to be shown
                         * as taking one optional `input`, whatever it actually
                         * wanted, so the only account of its arguments the model
                         * ever got was the sentence above. A declared parameter is
                         * required: a tool that asked for it is a tool that needs it.
                         */
                        parameters = tool.params.map { param ->
                            ToolParameterSpec(
                                name = param.name,
                                description = meaning(param.type),
                                required = true,
                            )
                        },
                    ),
                )
            }

        /*
         * And the plugin tools the grant list names, after everything else has
         * claimed its name: the resolution order is the shadow rule, and a
         * plugin's names carry its key precisely so this stays theoretical.
         * The declaration is the schema - a plugin wrote a tool's description
         * for exactly this reader, which is what tools() exists for.
         */
        // The core's names as well as this half's: a plugin tool named like a
        // built-in must not shadow it just because the two are now built apart.
        val taken = map { it.name }.toSet() + builtIn
        pluginTools.granted(agent, except = taken).forEach { tool ->
            add(
                ToolSpec(
                    name = tool.name,
                    description = tool.description ?: "One of this installation's plugin tools.",
                    parameters = tool.params.map { param ->
                        ToolParameterSpec(
                            name = param.name,
                            description = describe(param),
                            /*
                             * What the plugin said, rather than true for
                             * everything. A tool whose parameter may be left
                             * out now says so in the schema - which is where a
                             * model actually reads it - instead of in a
                             * sentence explaining which value means "unset".
                             */
                            required = param.required,
                        )
                    },
                ),
            )
        }
    }

    /**
     * What one parameter is, and what happens if it is not given.
     *
     * The default is named because a model choosing whether to pass something
     * is better off knowing what it would get - "how many come back, 20 if not
     * given" answers the question the sentinel convention used to answer
     * badly.
     */
    private fun describe(param: FunctionParam): String {
        val said = meaning(param.type)
        if (param.required) return said
        val held = param.defaultJson
        return if (held == null) "$said. Optional." else "$said. Optional; $held if not given."
    }

    /**
     * What to put in one argument, in a sentence.
     *
     * Everything in a tool schema is declared a string on the way out, so the
     * shape a parameter wants can only be said in words. An object parameter is
     * described as a JSON object rather than by the name of the object it
     * points at: that name means something in the editor, where the shape is a
     * click away, and nothing at all to a model that has never seen it.
     */
    private fun meaning(type: ValueType): String = when (type) {
        ValueType.STRING -> "Text."
        ValueType.NUMBER -> "A number."
        ValueType.BOOLEAN -> "true or false."
        ValueType.OBJECT, ValueType.MAP -> "A JSON object."
        ValueType.ARRAY -> "A JSON array."
        // Said as what a model can actually supply: it has no picker, so what it
        // has to produce is the number on the connection's page.
        ValueType.CONNECTION -> "The id of one of this workspace's connections."
        // Never stored on a parameter; only a function's return type is nothing.
        ValueType.NONE -> "Nothing."
    }

    /**
     * Runs one call and returns what to hand back, as JSON text.
     *
     * Never throws. A tool that failed is a fact the model should be told, not a
     * failed conversation — it can apologise, try another way, or answer without
     * it, and any of those beats the whole exchange dying because a lookup did.
     */
    fun run(agent: Agent, call: ToolCall, sessionId: Long? = null): String = try {
        /*
         * orknux's own, and only for an agent granted them.
         *
         * Checked before the rest by the prefix the surface owns, so a model
         * that guessed the name of a tool it was never offered is refused here
         * rather than reaching the workspace through a name it made up.
         */
        if (orknux.handles(call.name)) {
            if (agent.orknuxAccess) {
                orknux.run(scopeFor(agent), call.name, call.arguments)
            } else {
                mapper.writeValueAsString(mapOf("error" to "This agent has not been given access to orknux"))
            }
        } else if (shells.handles(call.name)) {
            /*
             * The shells, checked by name for the same reason orknux is checked
             * by prefix: a model that guessed the name of a tool it was never
             * offered is refused by the thing that would otherwise run it, and
             * not only by the menu it was never shown. ShellTools checks the
             * grant itself and says so in the words the model needs.
             */
            shells.run(agent, call.name, call.arguments)
        } else if (agentTools.handles(call.name)) {
            /*
             * Asking another agent, checked here as well as left off the menu -
             * the rule orknux and the shells keep - and by the thing that knows
             * which agents this one was granted.
             */
            agentTools.run(agent, call.arguments)
        } else if (connectionTools.handles(call.name)) {
            /*
             * The connection finder, checked here as well as left off the menu:
             * the rule orknux and the shells keep, and it is the thing that
             * knows what this agent was granted.
             */
            connectionTools.run(agent, call.arguments)
        } else if (call.name in ARTIFACT_TOOL_NAMES && !agent.artifactAccess) {
            /*
             * Refused here as well as left off the menu.
             *
             * The rule orknux and the shells already keep: a model that guessed
             * the name of a tool it was never offered is refused by the thing
             * that would otherwise run it, and not only by the menu it was
             * never shown.
             */
            mapper.writeValueAsString(
                mapOf("error" to "This agent has not been given permission to save artifacts"),
            )
        } else when (call.name) {
            SAVE_ARTIFACT -> {
                val saving = savedArtifacts.save(
                    workspaceId = agent.workspaceId,
                    savedBy = agent.name,
                    name = argument(call, "name").orEmpty(),
                    description = argument(call, "description").orEmpty(),
                    content = argument(call, "content").orEmpty(),
                    // Anything but "true" is text: a model that sent the flag
                    // at all meant it, and a missing flag is the common case.
                    base64 = argument(call, "base64")?.trim()?.lowercase() == "true",
                )
                when (saving) {
                    is SavedArtifacts.Saving.Refused -> mapper.writeValueAsString(mapOf("error" to saving.reason))
                    is SavedArtifacts.Saving.Saved -> mapper.writeValueAsString(
                        savedAnswer(
                            saving.artifact.name,
                            saving.artifact.sizeBytes,
                            requireNotNull(saving.artifact.id),
                            base(),
                        ),
                    )
                }
            }

            BASE64_ENCODE -> mapper.writeValueAsString(
                mapOf("base64" to PluginCrypto.encoded(argument(call, "text").orEmpty().toByteArray(Charsets.UTF_8))),
            )

            BASE64_DECODE -> {
                val bytes = PluginCrypto.decoded(argument(call, "base64").orEmpty().trim())
                /*
                 * Read back only if it is text. Handing a model the replacement
                 * characters that decoding arbitrary bytes as UTF-8 produces is
                 * handing it something it will reason about as though it were
                 * the file.
                 */
                val said = bytes?.let {
                    runCatching {
                        Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(it)).toString()
                    }.getOrNull()
                }
                when {
                    bytes == null -> mapper.writeValueAsString(mapOf("error" to "That is not valid base64."))
                    said == null -> mapper.writeValueAsString(
                        mapOf("error" to "Those bytes are not UTF-8 text, so there is nothing to read back."),
                    )
                    else -> mapper.writeValueAsString(mapOf("text" to said))
                }
            }

            "skill_list" -> mapper.writeValueAsString(mapOf("skills" to skills.list(agent)))

            "skill_load" -> {
                val name = argument(call, "name").orEmpty()
                val found = skills.load(agent, name)
                if (found == null) {
                    mapper.writeValueAsString(
                        mapOf("error" to "You have no skill called $name. Call skill_list for the ones you have."),
                    )
                } else {
                    mapper.writeValueAsString(mapOf("name" to found.name, "content" to found.content))
                }
            }

            "memory_search" -> mapper.writeValueAsString(
                mapOf(
                    "results" to memories.search(
                        agent = agent,
                        query = argument(call, "query"),
                        catalog = argument(call, "catalog"),
                    ),
                ),
            )

            "memory_save" -> mapper.writeValueAsString(
                memories.save(
                    agent = agent,
                    catalog = argument(call, "catalog"),
                    title = argument(call, "title"),
                    content = argument(call, "content"),
                ),
            )

            // Anything else is the workspace's own, and only if granted: a name
            // the model invented resolves to nothing rather than to code.
            else -> {
                val tool = workspaceTools.granted(agent).firstOrNull { it.name == call.name }
                val remote = mcpTools.resolve(agent, call.name)
                val declared = pluginTools.resolve(agent, call.name)
                when {
                    tool != null -> workspaceTools.call(agent, tool, call.arguments, sessionId)
                    // An MCP tool takes its own named arguments, so the whole
                    // object goes through rather than being unwrapped.
                    remote != null -> mcpTools.call(remote.first, remote.second, call.arguments)
                    declared != null -> pluginTools.call(agent, declared, call.arguments, sessionId)
                    else -> mapper.writeValueAsString(mapOf("error" to "There is no tool called ${call.name}"))
                }
            }
        }
    } catch (failure: Exception) {
        log.warn("Tool {} failed for agent {}", call.name, agent.name, failure)
        mapper.writeValueAsString(mapOf("error" to (failure.message ?: "That tool could not be run")))
    }

    /** Arguments arrive as a JSON object in a string, whichever shape asked. */
    /**
     * Where this installation is, for a link a model may copy anywhere.
     *
     * The same base the mails write from, falling back to the development
     * address rather than writing a path: a path has no host behind it
     * wherever the answer is read, and what comes of pasting one into a chat
     * is punctuation.
     */
    private fun base(): String =
        web.baseUrl.trim().trimEnd('/').ifEmpty { "http://localhost:5173" }

    private fun argument(call: ToolCall, name: String): String? = runCatching {
        mapper.readTree(call.arguments).path(name).stringValue()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun spec(descriptor: ToolDescriptor) = ToolSpec(
        name = descriptor.name,
        description = descriptor.description,
        parameters = descriptor.parameters.map { ToolParameterSpec(it.name, it.description, it.required) },
    )

    companion object {
        private val log = LoggerFactory.getLogger(AgentTools::class.java)

        const val SAVE_ARTIFACT = "save_artifact"
        const val BASE64_ENCODE = "base64_encode"
        const val BASE64_DECODE = "base64_decode"

        /**
         * Keeping a file, and the conversion getting a binary one there.
         *
         * The two base64 tools are the same operation the sandbox offers
         * plugins as `orknux.encoding`, over the same [PluginCrypto] - so a
         * model and a plugin encoding the same bytes cannot disagree, and
         * there is one implementation to be right rather than two.
         *
         * They exist because `save_artifact` takes base64 for anything that is
         * not text, and a model asked for base64 with no way to produce it
         * will produce something that looks like base64. A tool that actually
         * encodes is the difference between a saved PNG and a saved apology.
         */
        /** The three names the grant covers, for the refusal at the running end. */
        val ARTIFACT_TOOL_NAMES = setOf(SAVE_ARTIFACT, BASE64_ENCODE, BASE64_DECODE)

        /**
         * The field a tool puts a picture in when the model should see it, and
         * the one naming what kind it is.
         *
         * A tool answers with text, and a model that can see does not read
         * base64 - it reads an image part, which is a different thing in the
         * request. So a tool that has made a picture says so in its answer and
         * the loop lifts it out: the answer the model reads keeps a sentence
         * where the bytes were, and the bytes are hung on a turn of their own.
         * See [pictureIn].
         */
        const val PICTURE = "picture"
        const val PICTURE_TYPE = "pictureType"

        /** What a picture is hung on where the tool named no type. */
        const val PICTURE_DEFAULT_TYPE = "image/png"

        /**
         * As large a picture as is worth showing a model.
         *
         * Base64 characters rather than bytes, because that is what arrives.
         * Past this the tool's own answer is left exactly as it is: a model
         * being handed four megabytes of image is a context window spent on
         * one screenshot, and the tool's text still says what it made.
         */
        const val MOST_PICTURE_CHARS = 5 * 1024 * 1024

        /**
         * The picture in a tool's answer, where it put one there for the model
         * to look at.
         *
         * Null for every ordinary answer, which is almost all of them: this
         * reads two named fields and refuses anything else, so a tool that
         * happens to return a large string does not become an image by
         * accident.
         */
        fun pictureIn(result: String): Picture? = runCatching {
            val node = jackson.readTree(result)
            if (!node.isObject) return null
            val base64 = node.path(PICTURE).asString("")
            if (base64.isEmpty() || base64.length > MOST_PICTURE_CHARS) return null
            val type = node.path(PICTURE_TYPE).asString("").ifEmpty { PICTURE_DEFAULT_TYPE }
            if (!type.startsWith("image/")) return null
            Picture(dataUrl = "data:$type;base64,$base64", type = type, chars = base64.length)
        }.getOrNull()

        /**
         * The same answer with the bytes taken out, which is what the model is
         * told the tool said.
         *
         * The picture arrives separately and a copy of it in base64 helps
         * nobody: it is the single largest thing that can land in a context
         * window, it is unreadable, and the transcript keeps whatever is
         * written here for as long as the session lives.
         */
        fun withoutPicture(result: String, picture: Picture): String = runCatching {
            val node = jackson.readTree(result)
            if (!node.isObject) return result
            val edited = (node as tools.jackson.databind.node.ObjectNode).deepCopy()
            edited.put(PICTURE, "")
            edited.put("shown", true)
            edited.put("pictureBytes", picture.chars / 4 * 3)
            jackson.writeValueAsString(edited)
        }.getOrElse { result }

        /**
         * What `save_artifact` answers, and the only answer here with a link
         * in it.
         *
         * A saved artifact *is* a thing at an address - having one is what
         * saving it was for - so a model that wants to point at it has nothing
         * else to point with, and the markdown saves it composing the line by
         * hand around an id.
         *
         * Every other tool that makes bytes answers with a key instead. A
         * picture drawn for somebody in a chat is wanted *delivered*: handed a
         * link, a model pastes it, and a client with no document to resolve
         * the address against prints the construction. The difference is not
         * the format, it is whether the thing has an address worth having.
         *
         * Absolute, so the line still works where the answer is read: a path
         * has no host behind it once it has been copied into a mail or a
         * message.
         */
        fun savedAnswer(name: String, bytes: Long, id: Long, base: String): Map<String, Any> {
            val url = base.trimEnd('/') + "/api/artifacts/" + id
            return mapOf(
                "saved" to name,
                "bytes" to bytes,
                "url" to url,
                "markdown" to "[$name]($url)",
            )
        }

        /** One picture a tool made, on its way to a turn of its own. */
        data class Picture(val dataUrl: String, val type: String, val chars: Int) {

            /**
             * What is said above it.
             *
             * Something rather than nothing, because a turn of pure image
             * reads as a message with no words in it - and the model has just
             * called a tool, so saying which one it is looking at is the
             * difference between an answer and a guess.
             */
            fun noteFor(tool: String) = "The picture $tool just made, to look at."
        }

        private val jackson = ObjectMapper()

        val ARTIFACT_TOOLS = listOf(
            ToolSpec(
                name = SAVE_ARTIFACT,
                description = "Saves a file to this workspace's Artifacts, where people can find, view and " +
                    "download it later. " +
                    "Use it only when there is no better way to put the file in front of the person who " +
                    "wants it, or when they asked for an artifact. If you are answering somebody in a chat, " +
                    "attach the file to your reply instead, with whatever tool you have been given that " +
                    "sends or uploads one - because an artifact is reached by leaving the conversation and " +
                    "finding the run it belongs to, and a file saved here and mentioned in a message has been " +
                    "filed rather than delivered. This is the right place for something a later step or a " +
                    "later day needs, and for what nobody is waiting on now. " +
                    "Send text as it stands: an SVG, a " +
                    "CSV, JSON, markdown or any source you could read is text, and encoding it to base64 " +
                    "only makes it longer and easier to get wrong. base64 is for bytes that are not text, " +
                    "like a PDF or a PNG. Answers with the url it was saved at.",
                parameters = listOf(
                    ToolParameterSpec(
                        name = "name",
                        description = "What to call the file, extension and all, like diagram.svg. Not a path.",
                        required = true,
                    ),
                    ToolParameterSpec(
                        name = "description",
                        description = "What the file is, in a sentence. This is how anybody finds it again.",
                        required = true,
                    ),
                    ToolParameterSpec(
                        name = "content",
                        description = "The file itself: the text, or base64 when base64 is true.",
                        required = true,
                    ),
                    ToolParameterSpec(
                        name = "base64",
                        description = "true when content is base64 rather than text. Left out for text files.",
                    ),
                ),
            ),
            ToolSpec(
                name = BASE64_ENCODE,
                description = "Turns a short piece of text into base64 - a token, a header, a small value " +
                    "some API wants encoded. Not for whole files: everything it answers has to be copied " +
                    "out of here character for character, and a long one gets truncated on the way. A " +
                    "file that is text is saved and sent as text, never encoded first.",
                parameters = listOf(
                    ToolParameterSpec(name = "text", description = "The text to encode.", required = true),
                ),
            ),
            ToolSpec(
                name = BASE64_DECODE,
                description = "Reads a short piece of base64 back as text. Refuses base64 that does not " +
                    "decode to text, and is not the way to read a whole file.",
                parameters = listOf(
                    ToolParameterSpec(name = "base64", description = "The base64 to decode.", required = true),
                ),
            ),
        )

        /** Its own, because this is asked of text rather than of a running tool. */
        private val reader = ObjectMapper()

        /**
         * Whether what came back is this class saying the tool could not be
         * run, rather than the tool's own answer.
         *
         * Every refusal above is written as `{"error": …}` and nothing else
         * here writes that shape, so the question is answerable from the text —
         * which is what a caller has. Asked here rather than spelled out again
         * by whoever wants to know, so there is one description of a failed
         * call and it sits beside the four places that produce one.
         *
         * A tool of the workspace's own that chooses to answer `{"error": …}`
         * reads as a failure too. That is the right answer rather than a
         * limitation: a tool saying that is a tool reporting it could not do
         * what was asked, and the reader wants to know either way.
         */
        fun failed(result: String): Boolean = runCatching {
            val tree = reader.readTree(result)
            tree.isObject && tree.size() == 1 && tree.has("error")
        }.getOrDefault(false)
    }
}

/**
 * What an agent holds, split by whether it travels on every call.
 *
 * Issue #368. Everything an agent was granted used to be declared on every
 * request, and an agent granted more than the provider accepts could not answer
 * at all - OpenAI and Azure refuse the whole request over 128 tools, and the
 * person who asked got the provider's sentence about an array being too long.
 *
 * [core] is the handful an agent uses constantly and which there are few of.
 * [searchable] is what scales - a workspace's tools, the granted MCP servers',
 * the plugins' - and what a large grant list is actually made of. Below the
 * provider's ceiling the two are put back together and nothing about a round
 * changes; above it, the searchable half is found rather than carried.
 */
data class Offering(val core: List<ToolSpec>, val searchable: List<ToolSpec>)
