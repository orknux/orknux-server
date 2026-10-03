package io.mszymanski.orknux.server.plugin

import io.mszymanski.orknux.connector.connection.ConnectionCredentials
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionRepository
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionService
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionView
import io.mszymanski.orknux.server.variable.WorkspaceVariableRepository
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.time.OffsetDateTime

/**
 * What a plugin is told, for one workspace.
 *
 * A plugin declares what it needs; a workspace answers each one with a value it
 * types or with one of its own variables; this turns the pair into the object the
 * plugin is handed. That object is the whole of what a plugin knows about the
 * workspace it is running for, which is the useful part of asking for parameters
 * at all - what a plugin can reach is a list somebody can read.
 *
 * Nothing here logs a resolved value. A parameter is as likely to be an API token
 * as a hostname, and the two are indistinguishable by the time they are here.
 */
@Service
class PluginParameters(
    private val settings: PluginParameterSettingRepository,
    private val variables: WorkspaceVariableRepository,
    private val declarations: PluginDeclarations,
    /** Where a connection parameter's handle is checked against a real row. */
    private val connections: WorkspaceConnectionService,
    /** The row itself, for the one case a connection's address and credential cross. Issue #363. */
    private val connectionRows: WorkspaceConnectionRepository,
    /** The one place a stored credential is read; see [ConnectionCredentials]. */
    private val credentials: ConnectionCredentials,
    private val mapper: ObjectMapper,
) {

    /**
     * What this workspace's copy of the plugin is handed, as a JSON object.
     *
     * A parameter nothing usable is set for is left out rather than sent as null,
     * so a plugin can ask `if (this.settings.token === undefined)` and get the
     * answer it expects. A parameter the plugin does not declare is left out too,
     * whatever a row says: the declaration decides what crosses, not the settings
     * table, so a stale row cannot smuggle anything in.
     */
    fun settingsFor(plugin: Plugin, workspaceId: Long): String {
        val stored = byName(plugin, workspaceId)
        val json: ObjectNode = mapper.createObjectNode()

        val ownKinds = declarations.readConnectionTypes(plugin.declaredConnectionTypes, plugin.key, plugin.name)
            .map { it.id }
            .toSet()

        declarations.readParameters(plugin.declaredParameters).forEach { parameter ->
            val setting = stored[parameter.name] ?: return@forEach
            val value = resolved(parameter, setting, Opened(workspaceId, ownKinds)) ?: return@forEach
            json.set(parameter.name, mapper.readTree(value))
        }
        return mapper.writeValueAsString(json)
    }

    /**
     * The required parameters this workspace has not answered.
     *
     * "Not answered" covers more than an empty form: a reference whose variable has
     * been deleted, or which has never been given a value, is a parameter the
     * plugin will not receive, and saying it is set would be saying something
     * untrue about what will happen when it runs.
     */
    fun missingFor(plugin: Plugin, workspaceId: Long): List<String> {
        val stored = byName(plugin, workspaceId)
        return declarations.readParameters(plugin.declaredParameters)
            .filter { it.required }
            .filter { parameter -> resolved(parameter, stored[parameter.name]) == null }
            .map { it.name }
    }

    /** The plugin, its parameters and what they are set to, as one workspace sees it. */
    fun viewOf(plugin: Plugin, workspaceId: Long): WorkspacePluginView {
        val stored = byName(plugin, workspaceId)
        val declared = declarations.readParameters(plugin.declaredParameters)

        val parameters = declared.map { parameter ->
            val setting = stored[parameter.name]
            val variable = setting?.variableId?.let { variables.findByIdOrNull(it) }
            PluginParameterSettingView(
                name = parameter.name,
                description = parameter.description,
                type = parameter.type,
                connectionType = parameter.connectionType,
                required = parameter.required,
                secret = parameter.secret,
                options = parameter.options,
                literal = setting?.literalValue,
                /*
                 * That one is set, and not what it is.
                 *
                 * The whole of what makes typing a secret here safe: the value
                 * goes in, the screen learns it happened, and nothing carries
                 * it back out. A box that shows what it holds is a box that
                 * shows it to whoever is looking over your shoulder, and to
                 * every screenshot of this page.
                 */
                secretSet = setting?.secretValue != null,
                variableId = variable?.id?.toString(),
                // The name only. What it holds is read on the variables screen,
                // where reading it is recorded as something somebody did.
                variableName = variable?.name,
                missing = parameter.required && resolved(parameter, setting) == null,
            )
        }

        return WorkspacePluginView(
            plugin = plugin.view(
                declarations.read(plugin.declaredFunctions),
                declarations.readParameters(plugin.declaredParameters),
            ),
            parameters = parameters,
            missing = parameters.filter { it.missing }.map { it.name },
        )
    }

    /**
     * Sets one parameter to a value somebody typed, or to one of the workspace's
     * variables. Never both, and never neither.
     *
     * A name the plugin does not declare is refused rather than kept. Keeping it
     * would mean the settings table quietly held a second, larger idea of what the
     * plugin can be told than the plugin's own declaration does, and the whole
     * reason for declaring parameters is that the two agree.
     */
    @Transactional
    fun set(
        plugin: Plugin,
        workspaceId: Long,
        name: String,
        literal: String?,
        variableId: Long?,
        by: String,
    ): PluginParameterSetting {
        val parameter = declarations.readParameters(plugin.declaredParameters).firstOrNull { it.name == name }
            ?: throw PluginParameterUnknownException(name, plugin.key)

        if (literal != null && variableId != null) throw PluginParameterAmbiguousException(name)
        if (literal == null && variableId == null) throw PluginParameterEmptyException(name)

        /*
         * A connection parameter is answered by pointing at one of the
         * workspace's connections, and the pointing travels as the row's id in
         * the literal. Checked on its own terms before the scalar check, which
         * knows nothing of connections and refused every one - the page's
         * picker offered rows the save then called "not a connection", so the
         * one parameter type that names a row could never be answered at all.
         */
        if (parameter.type.equals(PluginDeclarations.CONNECTION, ignoreCase = true)) {
            if (variableId != null) throw PluginParameterNotValueException(name, "connection", "a variable")
            val id = literal?.trim()?.toLongOrNull()
                ?: throw PluginParameterNotValueException(name, "connection", literal.orEmpty())
            val connection = connections.workspaceConnection(id)
                ?.takeIf { it.workspaceId == workspaceId }
                ?.takeIf { ofKind(plugin.key, parameter.connectionType, it.type.name, it.pluginType) }
                ?: throw PluginParameterNotValueException(name, "connection", literal)
            log.debug("Plugin {} parameter {} points at connection {}", plugin.key, name, connection.id)
        } else if (literal != null) {
            /*
             * A secret typed here is kept as a secret rather than refused.
             *
             * It used to be refused outright, and the sentence said why: what
             * is typed into an ordinary parameter is stored as typed and shown
             * back on the page. That is a fact about the column rather than
             * about secrets, so the fix is a column that encrypts - the one
             * connections have used all along - and a screen that is told a
             * value is set without being told what it is.
             *
             * Still checked against its own type on the way in, so a number
             * parameter does not quietly accept prose because it is secret.
             */
            if (asJson(parameter.type, literal) == null) {
                throw PluginParameterNotValueException(name, parameter.type.lowercase(), literal)
            }
        }

        if (variableId != null) {
            val variable = variables.findByIdOrNull(variableId)
                ?: throw PluginParameterVariableElsewhereException(name)
            if (variable.workspaceId != workspaceId) throw PluginParameterVariableElsewhereException(name)
        }

        /*
         * Which column the value lands in, decided by what the plugin declared
         * rather than by what the caller asked for. A parameter that stops
         * being secret in a later version of a plugin leaves its old value
         * where it was - encrypted, and still read - rather than moving it
         * into the readable column behind somebody's back.
         */
        val asSecret = parameter.secret && literal != null

        val existing = settings.findByPluginIdAndWorkspaceIdAndName(requireNotNull(plugin.id), workspaceId, name)
        val row = existing?.apply {
            this.literalValue = if (asSecret) null else literal
            this.secretValue = if (asSecret) literal else null
            this.variableId = variableId
            this.lastModifiedAt = OffsetDateTime.now()
            this.lastModifiedBy = by
        } ?: PluginParameterSetting(
            pluginId = requireNotNull(plugin.id),
            workspaceId = workspaceId,
            name = name,
            literalValue = if (asSecret) null else literal,
            secretValue = if (asSecret) literal else null,
            variableId = variableId,
            lastModifiedBy = by,
        )
        return settings.save(row)
    }

    /** Unsets one parameter. A required one goes back to being reported as missing. */
    @Transactional
    fun clear(plugin: Plugin, workspaceId: Long, name: String) {
        settings.findByPluginIdAndWorkspaceIdAndName(requireNotNull(plugin.id), workspaceId, name)
            ?.let(settings::delete)
    }

    private fun byName(plugin: Plugin, workspaceId: Long): Map<String, PluginParameterSetting> =
        settings.findByPluginIdAndWorkspaceId(requireNotNull(plugin.id), workspaceId).associateBy { it.name }

    /**
     * What one parameter comes to, as JSON, or null when it comes to nothing.
     *
     * The parameter's declared type decides how the text is written, not the
     * variable's: the plugin said what it wanted, and a variable holding "8080" is
     * a usable answer to a parameter declared as a number.
     */
    /**
     * Whether a connection is of the kind a parameter names. Issue #363.
     *
     * A core kind matches by its type name. A plugin's own declared kind
     * matches by the id a connection stores - the plugin key and the declared
     * name joined - checked strictly where the key is known (the setting being
     * saved), and by the declared name alone where it is not (a value already
     * saved and checked once, being read back to build the handle).
     */
    private fun ofKind(pluginKey: String?, wanted: String?, type: String, pluginType: String?): Boolean {
        if (wanted == null) return true
        if (type == wanted) return true
        if (pluginType == null) return false
        return if (pluginKey != null) pluginType == "$pluginKey/$wanted" else pluginType.substringAfter('/') == wanted
    }

    /**
     * Which connections may cross with their address and credential, and for
     * which workspace. Only [settingsFor] and [connectionArguments] build one:
     * they are the paths into the sandbox, and every other reader of a
     * parameter only wants to know whether it is answered. Issue #363.
     */
    private class Opened(val workspaceId: Long, val ownKinds: Set<String>)

    private fun resolved(parameter: PluginParameterView, setting: PluginParameterSetting?, opened: Opened? = null): String? {
        if (setting == null) return null

        /*
         * A connection parameter names a row rather than holding a value, and
         * what crosses into the sandbox is a handle: the id and the kind. Never
         * the credential of a connection the server speaks to on the plugin's
         * behalf - the plugin has no network to use it on, and the server is
         * what makes the call. A host of the plugin's own kind is the one
         * exception, below.
         *
         * Checked against the connections this workspace actually has, so a
         * connection deleted after somebody pointed at it reads as unanswered
         * rather than as a handle to nothing. Issue #316.
         */
        if (parameter.type.equals(PluginDeclarations.CONNECTION, ignoreCase = true)) {
            val id = setting.literalValue?.toLongOrNull() ?: return null
            val connection = connections.workspaceConnection(id) ?: return null
            if (!ofKind(null, parameter.connectionType, connection.type.name, connection.pluginType)) return null
            return handleOf(connection, opened)
        }

        setting.literalValue?.let { return asJson(parameter.type, it) }
        // The encrypted one, read exactly like the plain one and never logged.
        setting.secretValue?.let { return asJson(parameter.type, it) }

        val variableId = setting.variableId ?: return null
        val variable = variables.findByIdOrNull(variableId)
        if (variable == null) {
            // Named, not valued: this is the one place a warning about a secret
            // could accidentally carry it.
            log.warn("A plugin parameter reads a variable that no longer exists: {}", parameter.name)
            return null
        }
        return asJson(parameter.type, variable.value)
    }

    /**
     * What crosses into the sandbox for one connection: the handle a setting
     * and an argument both arrive as. Null where the row has gone.
     */
    private fun handleOf(connection: WorkspaceConnectionView, opened: Opened?): String? {
        val id = connection.id
        val handle = mapper.createObjectNode()
        handle.put("id", id)
        handle.put("type", connection.type.name)
        // Which of the plugin's own kinds of host this is, where it is one. Issue #363.
        connection.pluginType?.let { handle.put("pluginType", it) }
        /*
         * A host of the plugin's own kind crosses with what reaching it
         * takes: the address, the auth kind, the credential and every
         * header to send. Issue #363.
         *
         * The rule above - never the credential - is about connections the
         * plugin did not define. A Slack connection is the server's to
         * speak to, through doors that keep the token on this side. A kind
         * the plugin declared has no such door and is not meant to have
         * one: the plugin is the only thing that knows how to talk to a
         * Prometheus, and it does so over `orknux.http` under the
         * NETWORK_REQUEST somebody already accepted for it. So the
         * connection is where its credential lives - encrypted, kept off
         * the plugin's settings page, chosen per workspace - and this is
         * the one place it is handed over.
         *
         * Strictly the plugin's own: the id a connection stores is matched
         * against this plugin's key and declared names, so a connection
         * wearing another plugin's kind - or one this plugin stopped
         * declaring - stays a handle. And only a connection of the
         * workspace the plugin runs for, whatever a stale row points at.
         */
        if (opened != null && connection.pluginType in opened.ownKinds && connection.workspaceId == opened.workspaceId) {
            val row = connectionRows.findByIdOrNull(id) ?: return null
            val target = credentials.target(row)
            handle.put("url", target.url)
            handle.put("authType", target.authType.name)
            target.secret?.let { handle.put("secret", it) }
            val headers = handle.putObject("headers")
            target.requestHeaders().forEach { (name, value) -> headers.put(name, value) }
        }
        return mapper.writeValueAsString(handle)
    }

    /* ------------------------------------------------ connection arguments ---- */

    /**
     * A function's or a tool's arguments, with every connection argument turned
     * into the handle a connection setting arrives as.
     *
     * A function or a tool may take a connection as an argument - the way one
     * plugin reaches several Prometheus servers rather than the one a setting
     * names - and what the caller has is the connection's id, or its name: a
     * workflow node's picker writes the id, a model writes whatever it was told.
     * Until this ran, that text reached the plugin as it was written, and a
     * plugin fronting a host of its own kind had no address to call.
     *
     * The same checks a setting gets, applied per call rather than on a save:
     * the connection is one of the calling workspace's, and of a kind the plugin
     * can use. What is handed over is [handleOf]'s - address and credential for
     * a host of the plugin's own kind, the id and type for anything else - read
     * through [ConnectionCredentials] as every credential is.
     *
     * @param params what the plugin declared, in order; the arguments are
     *   positional against them, and anything past the end (a workspace
     *   function's granted variables) is passed through untouched.
     * @param granted the connections an agent was granted, where an agent is
     *   the caller - see [ConnectionGrants]. Null for a workflow node and the
     *   editor's Run, where nobody is acting but the workspace itself.
     */
    fun connectionArguments(
        plugin: Plugin,
        workspaceId: Long,
        params: List<PluginFunctionParamView>,
        arguments: List<String>,
        granted: ConnectionGrants? = null,
    ): ConnectionArguments {
        if (params.none { it.type.equals(PluginDeclarations.CONNECTION, ignoreCase = true) }) {
            return ConnectionArguments.Handed(arguments)
        }
        val accepts = accepts(plugin)
        val opened = Opened(workspaceId, accepts.ownKinds)
        val held by lazy { reachable(workspaceId, granted) }
        val agent = granted != null

        val handed = arguments.mapIndexed { at, given ->
            val param = params.getOrNull(at)
            if (param == null || !param.type.equals(PluginDeclarations.CONNECTION, ignoreCase = true)) {
                return@mapIndexed given
            }
            val said = saidOf(given)
            val found = when (said) {
                // Left out: an optional connection the plugin decides about itself.
                Said.Nothing -> return@mapIndexed given
                is Said.Unreadable -> null
                is Said.Id -> held.firstOrNull { it.id == said.id }
                // Exact first, then regardless of case - and only where that is one connection.
                is Said.Name -> held.firstOrNull { it.name == said.name }
                    ?: held.filter { it.name.equals(said.name, ignoreCase = true) }.singleOrNull()
            } ?: return ConnectionArguments.Refused(refusal(param.name, said.text, accepts, held, agent))
            if (!accepts.fits(found)) {
                return ConnectionArguments.Refused(
                    "the ${param.name} argument names ${quoted(found.name)} (id ${found.id}), which is " +
                        "${kindOf(found)} connection rather than ${accepts.label ?: "one this plugin uses"}. " +
                        choices(accepts, held, agent),
                )
            }
            handleOf(found, opened)
                ?: return ConnectionArguments.Refused(refusal(param.name, said.text, accepts, held, agent))
        }
        return ConnectionArguments.Handed(handed)
    }

    /**
     * What a model is told a connection argument takes, for one plugin and one
     * workspace: the kind, both ways of naming one, and the ones there are.
     *
     * The list is the useful half. A model has no picker; without it, the id it
     * was meant to pass is a number it has to have been told somewhere else.
     */
    fun connectionArgumentMeaning(plugin: Plugin, workspaceId: Long, granted: ConnectionGrants? = null): String {
        val accepts = accepts(plugin)
        val held = reachable(workspaceId, granted)
        val whose = if (granted != null) "the" else "this workspace's"
        val kind = accepts.label?.let { "$it " } ?: ""
        val tail = if (granted != null) " you have been granted" else ""
        return "One of $whose ${kind}connections$tail, by its id or its exact name. " +
            choices(accepts, held, granted != null)
    }

    /**
     * What an argument may name: the workspace's connections, or - where an
     * agent is calling - only the ones it was granted. A connection the agent
     * was not granted is not merely refused but unseen, so a refusal cannot
     * say that it exists.
     */
    private fun reachable(workspaceId: Long, granted: ConnectionGrants?): List<WorkspaceConnectionView> =
        granted?.connections?.filter { it.workspaceId == workspaceId }
            ?: connections.workspaceConnections(workspaceId)

    /**
     * Which connections a plugin's connection argument may name.
     *
     * A function's or a tool's parameter says only `connection`, never which
     * kind - the plugin contract has no field for it - so the kind is the
     * plugin's own: a plugin that declares kinds of host takes an argument to
     * reach one of them, which is the whole reason it would take one (a setting
     * already names a single connection per workspace). A plugin declaring no
     * kinds takes any of the workspace's connections, as a handle.
     */
    private fun accepts(plugin: Plugin): Accepts {
        val own = declarations.readConnectionTypes(plugin.declaredConnectionTypes, plugin.key, plugin.name)
        if (own.isEmpty()) return Accepts(null, emptySet())
        return Accepts(own.joinToString(" or ") { it.label }, own.map { it.id }.toSet())
    }

    private class Accepts(val label: String?, val ownKinds: Set<String>) {
        fun fits(connection: WorkspaceConnectionView): Boolean = ownKinds.isEmpty() || connection.pluginType in ownKinds
    }

    /** What the caller wrote, read as an id, a name, or neither. */
    private sealed interface Said {
        /** As the caller wrote it, for a refusal to repeat. */
        val text: String

        data object Nothing : Said {
            override val text = ""
        }
        data class Id(val id: Long, override val text: String) : Said
        data class Name(val name: String, override val text: String) : Said
        data class Unreadable(override val text: String) : Said
    }

    private fun saidOf(given: String?): Said {
        if (given.isNullOrBlank()) return Said.Nothing
        val node = runCatching { mapper.readTree(given) }.getOrNull() ?: return Said.Unreadable(given)
        return when {
            node.isMissingNode || node.isNull -> Said.Nothing
            node.isIntegralNumber -> Said.Id(node.asLong(), node.asString())
            node.isString -> {
                val text = node.stringValue().trim()
                when {
                    text.isEmpty() -> Said.Nothing
                    text.toLongOrNull() != null -> Said.Id(text.toLong(), text)
                    else -> Said.Name(text, text)
                }
            }
            // A handle handed back - from a setting, or a step before - is
            // read by its id and checked again, never trusted as it stands.
            node.isObject && node.has("id") -> saidOf(mapper.writeValueAsString(node.get("id")))
            else -> Said.Unreadable(given)
        }
    }

    /**
     * Why an argument names nothing usable, with what would have been.
     *
     * Never more than a connection's id, name and kind: this goes back to a
     * model and into a run's log, and both are places a credential must not be.
     */
    private fun refusal(
        name: String,
        text: String,
        accepts: Accepts,
        held: List<WorkspaceConnectionView>,
        agent: Boolean,
    ): String {
        val kind = accepts.label?.let { "$it " } ?: ""
        val whose = if (agent) "the ${kind}connections you have been granted" else "this workspace's ${kind}connections"
        return "the $name argument has to name one of $whose, by its id or its name, and ${quoted(text)} is " +
            "none of them. " + choices(accepts, held, agent)
    }

    private fun choices(accepts: Accepts, held: List<WorkspaceConnectionView>, agent: Boolean): String {
        val usable = held.filter { accepts.fits(it) }.sortedBy { it.id }
        val kind = accepts.label?.let { "$it " } ?: ""
        if (usable.isEmpty()) {
            return if (agent) {
                "You have been granted no ${kind}connection; one has to be granted to you under this agent's " +
                    "Connections setting first."
            } else {
                "This workspace has no ${kind}connection; one has to be added on its Connections page first."
            }
        }
        val listed = usable.joinToString(", ") { "${it.id} (${it.name})" } + "."
        return if (agent) "The ${kind}connections you have been granted: $listed" else "This workspace's ${kind}connections: $listed"
    }

    private fun kindOf(connection: WorkspaceConnectionView): String {
        val kind = connection.pluginType ?: connection.type.name
        return if (kind.first().lowercaseChar() in "aeiou") "an $kind" else "a $kind"
    }

    /** Quoted, and cut short: whatever a caller wrote is echoed, never at length. */
    private fun quoted(text: String): String =
        "\"" + (if (text.length > ECHOED_CHARS) text.take(ECHOED_CHARS) + "..." else text) + "\""

    /** The text as the type says it should be written, or null when it is not that. */
    private fun asJson(type: String, held: String?): String? {
        val text = held?.trim() ?: return null
        if (text.isEmpty()) return null
        return when (type.uppercase()) {
            "STRING" -> mapper.writeValueAsString(held)
            "NUMBER" -> text.toBigDecimalOrNull()?.toString()
            "BOOLEAN" -> when (text.lowercase()) {
                "true" -> "true"
                "false" -> "false"
                else -> null
            }

            else -> null
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(PluginParameters::class.java)

        /**
         * How much of what a caller wrote a refusal repeats. Enough to recognise
         * a name or an id by; a model that pasted something long is told it
         * named nothing, not shown it back.
         */
        const val ECHOED_CHARS = 80
    }
}

/**
 * The connections an agent may name, read once by [io.mszymanski.orknux.server.chat.ConnectionTools.granted]
 * - the one place an agent's grants are turned into rows - and handed down so
 * the plugin side never learns what an agent is.
 */
class ConnectionGrants(val connections: List<WorkspaceConnectionView>)

/** A plugin call's arguments with its connections resolved, or why one could not be. */
sealed interface ConnectionArguments {
    data class Handed(val arguments: List<String>) : ConnectionArguments
    data class Refused(val reason: String) : ConnectionArguments
}
