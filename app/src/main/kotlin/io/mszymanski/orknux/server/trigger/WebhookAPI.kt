package io.mszymanski.orknux.server.trigger

import io.mszymanski.orknux.server.obj.ObjectProperty
import io.mszymanski.orknux.server.obj.PropertyKind
import io.mszymanski.orknux.server.action.FunctionScope
import io.mszymanski.orknux.server.action.WorkflowFunction
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.obj.WorkflowObjectRepository
import io.mszymanski.orknux.server.plugin.PluginParameters
import io.mszymanski.orknux.server.plugin.PluginCapabilities
import io.mszymanski.orknux.server.plugin.PluginPermissions
import io.mszymanski.orknux.server.plugin.PluginRepository
import io.mszymanski.orknux.server.plugin.PluginSources
import io.mszymanski.orknux.server.variable.VariableArguments
import io.mszymanski.orknux.server.action.ScriptImports
import io.mszymanski.orknux.server.action.ScriptImportsResult
import io.mszymanski.orknux.server.action.ScriptTimeouts
import io.mszymanski.orknux.workflow.script.PluginRunner
import io.mszymanski.orknux.workflow.script.ScriptResult
import io.mszymanski.orknux.workflow.script.ScriptRunner
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * Where a webhook trigger answers.
 *
 * Anything can start a workflow this way — a build finishing, a form being
 * submitted, another product's own webhook — which is what a connection and the
 * clock could not cover between them.
 *
 * **What is not there, and what is the wrong shape, are both 404.** A path
 * nothing listens on, a trigger that has been turned off, a body that is not
 * JSON at all, a body that is not the shape the trigger promises its workflows:
 * all answer the same way, because the caller of a webhook is the open internet.
 * Distinguishing "no such endpoint" from "wrong shape" tells whoever is probing
 * that something is there and what it expects.
 *
 * **A caller who fails to prove who they are gets 401**, which is a different
 * thing and worth saying: the endpoint exists, the request was understood, and
 * the answer is no. It is also written into the trigger's history, because a
 * webhook whose caller has the wrong secret looks exactly like a webhook nobody
 * is calling, and that difference is the whole of what somebody debugging it
 * needs.
 *
 * Everything else that goes wrong is recorded there too, for the same reason:
 * this endpoint answers a machine, and the history is the only place a person
 * finds out what it said.
 */
@RestController
@RequestMapping("/api/webhooks")
class WebhookAPI(
    private val triggers: WorkflowTriggerRepository,
    private val objects: WorkflowObjectRepository,
    private val runner: TriggerRunner,
    private val functions: WorkflowFunctionRepository,
    private val scripts: ScriptRunner,
    private val pluginRunner: PluginRunner,
    private val scriptImports: ScriptImports,
    private val plugins: PluginRepository,
    private val pluginParameters: PluginParameters,
    private val pluginPermissions: PluginPermissions,
    private val pluginCapabilities: PluginCapabilities,
    private val pluginSources: PluginSources,
    private val externals: VariableArguments,
    private val timeouts: ScriptTimeouts,
    private val mapper: ObjectMapper,
) {

    @PostMapping("/**")
    fun receive(
        @RequestBody(required = false) body: String?,
        @RequestHeader headers: Map<String, String>,
    ): ResponseEntity<Map<String, Any>> {
        // The path is read from the request rather than a variable, so a webhook
        // may have slashes in it: `build/finished` is one path, not two.
        val path = pathOf() ?: return notFound()
        val trigger = triggers.findByWebhookPath(path)?.takeIf { it.enabled } ?: return notFound()

        return try {
            answer(trigger, path, body, headers)
        } catch (failure: Exception) {
            // Whatever it was, the person who owns this trigger finds out here:
            // the caller is a machine and will only ever see a status code.
            log.warn("Webhook {} could not be handled", path, failure)
            runner.note(trigger, FiringOutcome.FAILED, "The request could not be handled: ${failure.message}")
            ResponseEntity.internalServerError().body(mapOf("error" to "The request could not be handled."))
        }
    }

    private fun answer(
        trigger: WorkflowTrigger,
        path: String,
        body: String?,
        headers: Map<String, String>,
    ): ResponseEntity<Map<String, Any>> {
        val sent = body?.takeIf { it.isNotBlank() }?.let { runCatching { mapper.readTree(it) }.getOrNull() }
        if (sent == null) {
            /*
             * A body that is not JSON is the wrong shape, and the wrong shape is
             * 404 here like everything else that is not a start.
             *
             * This answered 400 once, which was the one thing an anonymous
             * caller could get out of this endpoint that only a real path could
             * produce: send a full stop to a list of guessed names, and the ones
             * that came back 400 instead of 404 were the webhooks this
             * installation has armed. The owner still learns what happened, on
             * the line below, because what the caller may know and what the
             * owner needs to know are not the same thing.
             */
            log.debug("Webhook {} was called with something that is not JSON", path)
            runner.note(trigger, FiringOutcome.FAILED, "The request body was not JSON")
            return notFound()
        }

        /*
         * Who is calling, before what they sent.
         *
         * Asked before the shape on purpose: a caller who cannot prove they are
         * allowed to start anything has no business learning whether their body
         * was the right shape. Parsing had to come first, because the function
         * asked below is handed the body as JSON, and that costs nothing now
         * that a body which will not parse is answered the same 404 as a path
         * nothing listens on.
         */
        val allowed = authenticated(trigger, sent, body, headers)
        if (!allowed.yes) {
            log.debug("Webhook {} refused a caller: {}", path, allowed.detail)
            runner.note(trigger, FiringOutcome.UNAUTHENTICATED, allowed.detail)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "Not authenticated."))
        }

        val shape = trigger.objectId?.let { objects.findByIdOrNull(it) }
        if (shape == null || !matches(sent, shape.properties, depth = 0)) {
            log.debug("Webhook {} was called with a body that is not {}", path, shape?.name ?: "any known shape")
            runner.note(
                trigger,
                FiringOutcome.FAILED,
                "The request did not match ${shape?.name ?: "the expected object"}",
            )
            return notFound()
        }

        /*
         * The headers go with it, because for a good many senders the body does
         * not say what happened: GitHub names the event in `X-GitHub-Event` and a
         * workflow handed only the JSON cannot tell a push from a pull request.
         * What the run is handed is narrower than what the gatekeeper above was
         * asked with — see [WebhookDelivery] — since one is checking a signature
         * and the other is written into a row people read.
         */
        val started = runner.fire(trigger, sent, WebhookDelivery(path, headers))
        return ResponseEntity.accepted().body(mapOf("started" to started))
    }

    /** Whether the caller may start anything, and what to write down if not. */
    private data class Verdict(val yes: Boolean, val detail: String = "")

    /**
     * Asks the trigger's function whether this caller is allowed.
     *
     * The function is handed the request the way a node is handed its
     * parameters — by name, so a signature check takes `(headers, rawBody)` and
     * a shared-secret check takes `(body)` — and then its own external
     * parameters, which is where the secret it checks against comes from.
     *
     * Anything other than `true` is a no: a function that threw, that answered
     * an object, or that has been deleted since it was chosen. The caller is
     * refused and the reason is written into the history, because a webhook that
     * refuses everybody looks the same as one nobody calls.
     */
    private fun authenticated(
        trigger: WorkflowTrigger,
        sent: JsonNode,
        rawBody: String?,
        headers: Map<String, String>,
    ): Verdict {
        if (trigger.authType != WebhookAuthType.FUNCTION) return Verdict(true)

        val function = trigger.authFunctionId?.let { functions.findByIdOrNull(it) }
            ?: return Verdict(false, "The function this webhook authenticates with has been deleted")

        val request = mapOf(
            "body" to sent,
            "rawBody" to (rawBody ?: ""),
            // Lower-cased, because a header's case is the sender's whim and a
            // script should not have to guess which one it got.
            "headers" to headers.mapKeys { (name, _) -> name.lowercase() },
            "path" to (trigger.webhookPath ?: ""),
        )
        val byName = request.mapValues { (_, value) -> mapper.writeValueAsString(value) }
        val arguments = function.params.map { byName[it.name] ?: "null" } + externals.of(function)

        /*
         * A plugin's function is not this workspace's JavaScript — its source
         * column holds a note saying where the implementation lives — so it is
         * asked of the plugin, in the plugin's own sandbox, the same way a
         * condition asks one. Running the note as a script would fail, and a
         * failure here is a no: the webhook would refuse every caller and the
         * history would blame the gatekeeper for a mistake nobody made.
         */
        val call = if (function.scope == FunctionScope.PLUGIN && function.editedAt == null) {
            askPlugin(trigger, function, arguments)
        } else {
            when (val resolved = scriptImports.resolve(function.imports, function.libraries)) {
                is ScriptImportsResult.Broken -> ScriptResult.Failed(resolved.reason, 0)
                is ScriptImportsResult.Resolved -> scripts.call(
                    source = function.source,
                    functionName = function.name,
                    arguments = arguments,
                    modules = resolved.modules,
                    imports = resolved.imports,
                    on = trigger.workspaceId,
                    timeoutMillis = timeouts.forFunction(function.timeoutSeconds, trigger.workspaceId),
                )
            }
        }

        return when (val result = call) {
            is ScriptResult.Returned -> when (result.json) {
                "true" -> Verdict(true)
                else -> Verdict(false, "${function.name} did not accept the caller")
            }

            is ScriptResult.Failed -> Verdict(false, "${function.name} ${result.reason}")
        }
    }

    /**
     * Asks a function one of the plugins declared.
     *
     * Unloaded, or configured with something still missing, is a no like any
     * other — there is nobody to ask and a caller cannot be let in on that — but
     * the reason is written into the history in those words, so whoever set the
     * webhook up reads "the plugin has not been told its secret" rather than
     * watching every request bounce.
     */
    private fun askPlugin(
        trigger: WorkflowTrigger,
        function: WorkflowFunction,
        arguments: List<String>,
    ): ScriptResult {
        val plugin = function.pluginId?.let { plugins.findByIdOrNull(it) }
            ?: return ScriptResult.Failed("is declared by a plugin that is no longer loaded", 0)

        val missing = pluginParameters.missingFor(plugin, trigger.workspaceId)
        if (missing.isNotEmpty()) {
            return ScriptResult.Failed(
                "cannot run: the ${plugin.key} plugin has not been told " +
                    missing.joinToString(", ") + ". Set it on this workspace's plugins page.",
                0,
            )
        }

        // The name the plugin gave it, not the prefixed one a workspace picks it
        // by: the prefix exists so two plugins can both declare `verify`, and the
        // plugin never agreed to answer to it.
        val declared = function.name.removePrefix("${plugin.key}_")
        return pluginRunner.call(
            pluginSources.sourceOf(plugin),
            declared,
            arguments,
            pluginParameters.settingsFor(plugin, trigger.workspaceId),
            // What a person accepted for this plugin, and nothing else. Read
            // per call from this plugin's row, so one plugin's agreement cannot
            // reach another's context.
            pluginPermissions.grantedTo(plugin),
            // And what it may ask the server to do, read the same way and
            // from its own row: a capability reaches outside the sandbox, so
            // it is granted apart from the permissions above.
            pluginCapabilities.grantedTo(plugin),
            on = trigger.workspaceId,
            libraries = pluginSources.librariesOf(plugin),
            // A webhook waits on this one, so it is bounded like any other
            // function rather than by the plugin's loading bound.
            timeoutMillis = timeouts.forFunction(function.timeoutSeconds, trigger.workspaceId),
        )
    }

    /** What was called, with the mapping's own prefix taken off. */
    private fun pathOf(): String? {
        val attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
        val request = (attributes as? org.springframework.web.context.request.ServletRequestAttributes)?.request
        val whole = request?.requestURI ?: return null
        return whole.substringAfter(PREFIX, missingDelimiterValue = "").trim('/').ifEmpty { null }
    }

    /**
     * Whether what arrived is the shape the trigger promised.
     *
     * Every property has to be there and be the right kind. Extra fields are
     * allowed: a system that adds one to its own webhook has not broken the
     * contract this trigger's workflows were written against.
     *
     * An object property with a shape of its own is checked against that shape,
     * which is why this recurses — bounded, because objects can point at each
     * other and a request should not be able to make us walk a circle.
     */
    private fun matches(sent: JsonNode, properties: List<ObjectProperty>, depth: Int): Boolean {
        if (!sent.isObject) return false
        if (depth > MAX_DEPTH) return true

        return properties.all { property ->
            val value = sent.get(property.name) ?: return@all false
            when (property.kind) {
                PropertyKind.STRING -> value.isTextual
                PropertyKind.NUMBER -> value.isNumber
                PropertyKind.BOOLEAN -> value.isBoolean
                PropertyKind.ARRAY -> value.isArray && value.all { held -> element(held, property, depth) }
                PropertyKind.OBJECT -> value.isObject && nested(value, property, depth)
            }
        }
    }

    /** An object property is checked against the object it names, when it names one. */
    private fun nested(value: JsonNode, property: ObjectProperty, depth: Int): Boolean {
        val shape = property.refObjectId?.let { objects.findByIdOrNull(it) } ?: return true
        return matches(value, shape.properties, depth + 1)
    }

    /** One entry of an array: the scalar it holds, or the object it names. */
    private fun element(held: JsonNode, property: ObjectProperty, depth: Int): Boolean = when (property.elementKind) {
        PropertyKind.STRING -> held.isTextual
        PropertyKind.NUMBER -> held.isNumber
        PropertyKind.BOOLEAN -> held.isBoolean
        PropertyKind.ARRAY -> held.isArray
        PropertyKind.OBJECT, null -> held.isObject && nested(held, property, depth)
    }

    private fun notFound(): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "Not found."))

    private companion object {
        val log = LoggerFactory.getLogger(WebhookAPI::class.java)

        const val PREFIX = "/api/webhooks/"

        /** Deep enough for a shape somebody drew, shallow enough to be a bound. */
        const val MAX_DEPTH = 5
    }
}
