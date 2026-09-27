package io.mszymanski.orknux.server.embedded

import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.action.FunctionParam
import io.mszymanski.orknux.server.action.FunctionScope
import io.mszymanski.orknux.server.action.WorkflowFunction
import io.mszymanski.orknux.server.action.ValueType
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.plugin.PluginCapabilities
import io.mszymanski.orknux.server.plugin.PluginPermissions
import io.mszymanski.orknux.workflow.script.PluginInspection
import io.mszymanski.orknux.workflow.script.PluginRunner
import io.mszymanski.orknux.workflow.script.ScriptResult
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.io.Resource
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

/**
 * What Orknux can do itself, out of bundles it ships. Issue #501.
 *
 * Making a PDF and drawing a chart are not integrations with somebody else's
 * system; they are things the product does. They arrived as plugins because a
 * sandboxed bundle had no other door, and the cost showed: a page offering to
 * install and uninstall them, a row that could be switched off by accident, and
 * a catalogue listing for something the release already contained.
 *
 * So they are embedded. The bundle is read from `resources/embedded/<key>/` at
 * boot, evaluated in the same sandbox every script runs in, and what it declares
 * becomes the product's own surface: tools an agent holds the way it holds the
 * clock and the scratchpads. There is no row in `plugin`, nothing on the Plugins
 * page, and nothing to install.
 *
 * **Why a sandbox at all, for our own code.** Because the bundle is JavaScript -
 * jsPDF and a chart renderer, neither with a JVM equivalent worth writing - and
 * because the sandbox is what bounds it: a runaway loop laying out somebody's
 * report stops at the installation's timeout rather than holding a thread.
 *
 * **What it is granted is what it declares**, taken as accepted. Nobody is
 * asked, because there is no decision to make: refusing a capability the product
 * itself needs would leave a feature that cannot work and no way to say why.
 *
 * The bundles are built in orknux-extension and vendored here, since this
 * repository has no node.
 */
@Service
class EmbeddedCapabilities(
    private val runner: PluginRunner,
    private val permissions: PluginPermissions,
    private val capabilities: PluginCapabilities,
    private val installation: InstallationSettings,
    /** Where a graph finds what these declare; see [register]. */
    private val functions: WorkflowFunctionRepository,
    private val mapper: ObjectMapper,
    /**
     * The capabilities written here rather than run in a sandbox. Issue #505.
     *
     * They answer first, and a bundle only ever answers a name none of them
     * claims. That is what lets a bundle be replaced one at a time: the pdf,
     * the diagrams and the charts are JVM code now, and while anything is left
     * in `resources/embedded` it goes on working beside them.
     */
    private val native: List<EmbeddedCapability> = emptyList(),
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** One bundle, read once, with what it declared. */
    data class Embedded(
        val key: String,
        val name: String,
        val version: String?,
        val source: String,
        val read: PluginInspection.Read,
    )

    @Volatile
    private var held: List<Embedded> = emptyList()

    /** Everything the release brought, in the order their names sort. */
    fun all(): List<Embedded> = held

    @EventListener(ApplicationReadyEvent::class)
    fun read() {
        val manifests = runCatching { PathMatchingResourcePatternResolver().getResources(MANIFESTS) }
            .getOrElse { why ->
                log.warn("What this release embeds could not be read: {}", why.message)
                return
            }

        held = manifests.sortedBy { it.description }.mapNotNull { manifest ->
            runCatching { one(manifest) }.getOrElse { why ->
                /*
                 * A bundle that will not load is a feature missing, not a
                 * server that refuses to start: the rest of the product works
                 * and the log says which capability is absent.
                 */
                log.warn("An embedded capability was not read: {}", why.message)
                null
            }
        }
        native.forEach { log.info("Orknux brings {}", it.name) }
        held.forEach { log.info("Orknux brings {} {}, out of a bundle", it.key, it.version.orEmpty()) }
        runCatching { register() }.onFailure { log.warn("Embedded functions were not registered: {}", it.message) }
    }

    /**
     * The workflow rows for what the bundles declare. Issue #501.
     *
     * A graph points at a function by its row, so the rows have to exist for a
     * node to be wired to one - the same reason a plugin's declarations are
     * materialised rather than read from the plugin every time the editor draws
     * a picker. These carry no plugin, which is the whole of the difference.
     *
     * Kept rather than rewritten where a row is already there: the migration
     * that made these embedded re-pointed the plugin's rows instead of deleting
     * them, precisely so a graph holding one by id went on working, and writing
     * a fresh row here would undo that.
     */
    @Transactional
    fun register() {
        /*
         * The native ones first, and by the same rules: a row that already
         * exists is re-pointed rather than replaced, so a graph holding one by
         * id goes on working when a bundle becomes JVM code underneath it.
         * That is the whole reason the migration re-pointed rather than deleted.
         */
        native.forEach { capability ->
            capability.functions().forEach { declared ->
                write(
                    name = capability.key + "_" + declared.name,
                    description = declared.description,
                    returnType = declared.returnType,
                    params = declared.params.map { param ->
                        FunctionParam(
                            param.name,
                            param.type,
                            required = param.required,
                            defaultJson = param.defaultJson,
                        )
                    },
                )
            }
        }

        held.forEach { one ->
            one.read.functions.forEach { declared ->
                val name = one.key + "_" + declared.name
                val existing = functions.findByScopeAndName(FunctionScope.EMBEDDED, name)
                    ?: functions.findByScopeAndName(FunctionScope.PLUGIN, name)
                val params = declared.params.map { param ->
                    FunctionParam(
                        param.name,
                        valueType(param.type),
                        required = param.required,
                        defaultJson = param.default,
                    )
                }
                val row = existing?.apply {
                    // An edited row runs from its own code and is left alone,
                    // the way an edited plugin function is.
                    if (editedAt != null) return@forEach
                    this.scope = FunctionScope.EMBEDDED
                    this.pluginId = null
                    this.description = declared.description
                    this.returnType = valueType(declared.returnType)
                    /*
                     * And whatever shape the plugin row pointed at goes with
                     * the plugin. `ck_workflow_function_return_object` binds
                     * the two together in both directions, so a row re-pointed
                     * from OBJECT to MAP that kept its object id is refused -
                     * which is how this was found: the whole registration
                     * rolled back and the tools worked while the graph editor
                     * had nothing to offer.
                     */
                    this.returnObjectId = null
                    this.params = params.toMutableList()
                    this.lastModifiedAt = OffsetDateTime.now()
                    this.lastModifiedBy = "orknux"
                } ?: WorkflowFunction(
                    workspaceId = null,
                    scope = FunctionScope.EMBEDDED,
                    name = name,
                    description = declared.description,
                    source = "Brought by Orknux itself; there is no code here to read.",
                    returnType = valueType(declared.returnType),
                    params = params.toMutableList(),
                    lastModifiedAt = OffsetDateTime.now(),
                    lastModifiedBy = "orknux",
                )
                functions.save(row)
            }
        }
    }

    /**
     * The server's name for a type the bundle wrote in its own spelling.
     *
     * A declaration says `string` and `number`, lowercase, because that is how
     * the TypeScript a bundle is written against spells them - the plugin path
     * matches them the same way, ignoring case.
     *
     * Anything shaped is a map. A shape a bundle exports - `Document`,
     * `Preview` - has no name this side, and bare `object` names one of a
     * *workspace's* definitions, which an embedded function cannot mean because
     * it belongs to every workspace at once. A plugin answers this by
     * materialising its shapes as rows and pointing at one; an embedded bundle
     * has no plugin to hang those off, and `ck_workflow_function_return_object`
     * refuses an OBJECT with nothing to point at. So: map. The row exists to be
     * wired to, and a map is the honest answer for a value whose fields this
     * side does not hold a definition of.
     */
    private fun valueType(named: String): ValueType =
        when (val held = ValueType.entries.firstOrNull { it.name.equals(named.trim(), ignoreCase = true) }) {
            null, ValueType.OBJECT -> ValueType.MAP
            else -> held
        }

    /**
     * One function row, written or re-pointed.
     *
     * The rules are the same whichever side declared it, which is the point of
     * having one of these: an existing row keeps its id, an edited one is left
     * alone, and OBJECT never survives with an object id it no longer has.
     */
    private fun write(name: String, description: String?, returnType: ValueType, params: List<FunctionParam>) {
        val existing = functions.findByScopeAndName(FunctionScope.EMBEDDED, name)
            ?: functions.findByScopeAndName(FunctionScope.PLUGIN, name)
        val row = existing?.apply {
            // An edited row runs from its own code and is left alone, the way
            // an edited plugin function is.
            if (editedAt != null) return
            this.scope = FunctionScope.EMBEDDED
            this.pluginId = null
            this.description = description
            this.returnType = returnType
            this.returnObjectId = null
            this.params = params.toMutableList()
            this.lastModifiedAt = OffsetDateTime.now()
            this.lastModifiedBy = "orknux"
        } ?: WorkflowFunction(
            workspaceId = null,
            scope = FunctionScope.EMBEDDED,
            name = name,
            description = description,
            source = "Brought by Orknux itself; there is no code here to read.",
            returnType = returnType,
            params = params.toMutableList(),
            lastModifiedAt = OffsetDateTime.now(),
            lastModifiedBy = "orknux",
        )
        functions.save(row)
    }

    private fun one(manifest: Resource): Embedded {
        val said = mapper.readTree(manifest.inputStream.use { it.readBytes() })
        val key = said.path("key").stringValue().orEmpty().trim()
        require(key.isNotEmpty()) { "a manifest names no key" }
        val file = said.path("path").stringValue().orEmpty().trim().ifEmpty { "$key.js" }
        val source = manifest.createRelative(file).let { beside ->
            require(beside.exists()) { "$file is not beside its manifest" }
            beside.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
        }

        val read = when (val answered = runner.inspect(source, emptyList(), installation.pluginTimeoutMillis())) {
            is PluginInspection.Read -> answered
            is PluginInspection.Unreadable -> throw IllegalStateException("$key: " + answered.reason)
        }
        return Embedded(
            key = key,
            name = said.path("name").stringValue()?.trim()?.ifEmpty { null } ?: key,
            version = said.path("version").stringValue()?.trim()?.ifEmpty { null },
            source = source,
            read = read,
        )
    }

    /* -------------------------------------------------- what a workflow calls */

    /**
     * One function call, in the bundle that declares it. Issue #501.
     *
     * The workflow half of the same door the tools come through: a graph names
     * `pdf_fromHtml` the way it always did, and what answers is the release's
     * own bundle rather than a plugin row that has to exist.
     */
    fun callFunction(name: String, arguments: List<String>, workspaceId: Long, sessionId: Long?): ScriptResult? {
        nativeFunctionFor(name)?.let { (capability, own) ->
            return runCatching { capability.call(own, arguments, workspaceId, sessionId) }
                .getOrElse { why -> ScriptResult.Failed(why.message ?: "it did not work", 0) }
        }

        val one = held.firstOrNull { one -> one.read.functions.any { one.key + "_" + it.name == name } } ?: return null
        val declared = one.read.functions.first { one.key + "_" + it.name == name }
        return runner.call(
            source = one.source,
            functionName = declared.name,
            arguments = arguments,
            permissions = permissions.validated(one.read.permissions),
            capabilities = capabilities.validated(one.read.capabilities),
            on = workspaceId,
            surface = "functions",
            sessionId = sessionId,
            timeoutMillis = installation.pluginTimeoutMillis(),
        )
    }

    /* ---------------------------------------------------- what an agent holds */

    /**
     * The tools, named `<key>_<tool>` the way this bundle's names have always
     * been written, so an agent that knew `pdf_fromHtml` knows it still.
     */
    fun toolSpecs(): List<ToolSpec> = native.flatMap { one ->
        one.tools().map { it.spec(one.key) }
    } + held.flatMap { one ->
        one.read.tools.map { tool ->
            ToolSpec(
                name = one.key + "_" + tool.name,
                description = tool.description ?: "One of the tools Orknux brings.",
                parameters = tool.params.map { param ->
                    ToolParameterSpec(
                        name = param.name,
                        /*
                         * A declared parameter carries no prose of its own, so
                         * its type stands in - the same stand-in a plugin's
                         * tool gets, and for the same reason: a model reading
                         * "string" learns more than it does from an empty line.
                         */
                        description = param.type.lowercase(),
                        required = param.required,
                    )
                },
            )
        }
    }

    fun handles(name: String): Boolean =
        nativeFor(name) != null || held.any { one -> one.read.tools.any { one.key + "_" + it.name == name } }

    /** The capability whose own name this is, where one of ours claims it. */
    private fun nativeFor(name: String): Pair<EmbeddedCapability, String>? = native.firstNotNullOfOrNull { one ->
        one.tools().firstOrNull { one.key + "_" + it.name == name }?.let { one to it.name }
    }

    private fun nativeFunctionFor(name: String): Pair<EmbeddedCapability, String>? =
        native.firstNotNullOfOrNull { one ->
            one.functions().firstOrNull { one.key + "_" + it.name == name }?.let { one to it.name }
        }

    /**
     * The call's arguments with every map parameter that arrived as text turned
     * back into the object it spells. Issue #537.
     *
     * Every parameter goes to the model typed as a string, so a model asked for
     * `values` or `headers` writes them as the text of an object - and a tool
     * reading an object found text and refused it, in words that told the model
     * to send exactly what it had sent. Unwrapped once, here, rather than in each
     * tool: the next map parameter somebody adds is then right the day it lands.
     */
    private fun unwrapped(arguments: String, params: List<EmbeddedParam>): String {
        val maps = params.filter { it.type == ValueType.MAP }.map { it.name }
        if (maps.isEmpty()) return arguments
        val sent = runCatching { mapper.readTree(arguments) }.getOrNull() as? tools.jackson.databind.node.ObjectNode
            ?: return arguments
        var changed = false
        maps.forEach { name ->
            val held = sent.get(name)
            if (held != null && held.isString) {
                val read = runCatching { mapper.readTree(held.stringValue()) }.getOrNull()
                if (read != null && read.isObject) {
                    sent.set(name, read)
                    changed = true
                }
            }
        }
        return if (changed) mapper.writeValueAsString(sent) else arguments
    }

    /**
     * One tool call, in the bundle that declares it.
     *
     * Positional and in the order the declaration lists them, which is how the
     * sandbox takes arguments - the same assembly a plugin's tool gets, minus
     * the workspace settings a plugin can be told, which an embedded bundle has
     * no way to be told and no reason to want.
     */
    fun run(name: String, arguments: String, workspaceId: Long, sessionId: Long?): String {
        nativeFor(name)?.let { (capability, own) ->
            val declared = capability.tools().firstOrNull { it.name == own }?.params.orEmpty()
            return runCatching { capability.run(own, unwrapped(arguments, declared), workspaceId, sessionId) }
                .getOrElse { why ->
                    log.warn("The embedded tool {} failed: {}", name, why.message)
                    refusal(why.message ?: "it did not work")
                }
        }

        val one = held.firstOrNull { held -> held.read.tools.any { held.key + "_" + it.name == name } }
            ?: return refusal("There is no tool called " + name + ".")
        val tool = one.read.tools.first { one.key + "_" + it.name == name }

        val sent = runCatching { mapper.readTree(arguments) }.getOrNull()
        val positional = tool.params.map { param ->
            val given = sent?.path(param.name)
            when {
                given == null || given.isMissingNode || given.isNull -> param.default ?: "null"
                given.isTextual -> mapper.writeValueAsString(given.stringValue())
                else -> given.toString()
            }
        }

        val answered = runner.call(
            source = one.source,
            functionName = tool.name,
            arguments = positional,
            permissions = permissions.validated(one.read.permissions),
            capabilities = capabilities.validated(one.read.capabilities),
            on = workspaceId,
            surface = "tools",
            sessionId = sessionId,
            timeoutMillis = installation.pluginTimeoutMillis(),
        )
        return when (answered) {
            is ScriptResult.Returned -> answered.json ?: mapper.writeValueAsString(mapOf("result" to null))
            is ScriptResult.Failed -> {
                log.warn("The embedded tool {} failed: {}", name, answered.reason)
                refusal(answered.reason)
            }
        }
    }

    private fun refusal(said: String): String = mapper.writeValueAsString(mapOf("error" to said))

    private companion object {
        /** Every bundle ships the same manifest a plugin does; the door is what changed. */
        const val MANIFESTS = "classpath*:embedded/*/plugin.json"
    }
}
