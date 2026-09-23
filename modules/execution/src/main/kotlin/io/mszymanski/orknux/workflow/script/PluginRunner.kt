package io.mszymanski.orknux.workflow.script

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotAccess
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.ResourceLimits
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.io.IOAccess
import org.springframework.boot.context.properties.ConfigurationProperties
import org.graalvm.polyglot.proxy.ProxyExecutable
import org.graalvm.polyglot.proxy.ProxyObject
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicReference

/**
 * Loads a plugin, asks it what it is, and runs what it declared.
 *
 * A deliberate copy of [ScriptRunner] rather than a generalisation of it, and the
 * duplication is the point. Plugins are going to be given authority a workspace's
 * functions must never have, and if one class configured both, granting a plugin
 * something would mean writing a condition — and a mistake in that condition would
 * hand it to every function anybody has written. Two classes, two flat
 * configurations, and the function one has no branch that could turn a capability
 * on.
 *
 * **The contract is a class, not a shape.** The sandbox defines `OrknuxPlugin`
 * before the plugin is evaluated, and a plugin has to extend it. That is checked by
 * prototype rather than by probing for keys, so "this is not a plugin" is answered
 * before anything is called, and a method left unimplemented fails with the base
 * class saying which one — rather than the server guessing why a key was missing.
 *
 * **A plugin knows only what it was told.** Its parameters arrive as `this.settings`
 * and are the whole of what it can see of the workspace it is running for. There is
 * no clock, no host, no way to ask — so what a plugin can reach is a list somebody
 * filled in, which is the point of it having to declare them.
 */
@Service
class PluginRunner(
    private val properties: PluginProperties,
    /**
     * What a plugin may ask the server to do on its behalf, or null where
     * nothing offers it.
     *
     * Optional so this module goes on standing up without one - a plugin with no
     * capabilities never reaches it, which is every plugin until one declares
     * something. See [PluginHost].
     */
    private val host: PluginHost? = null,
    /**
     * The AI session's scratchpad, for `orknux.session.store`. Null in tests
     * and installations that wire none; the helper then says there is no
     * store here.
     */
    private val scratch: SessionScratch? = null,
) {

    /**
     * The contract, with this installation's helpers filled in.
     *
     * Built once per runner: the text is the same every time, and the log
     * threshold is a setting rather than something a plugin decides. It lives
     * here rather than beside `CONTRACT` because that is a companion constant
     * and cannot see the properties.
     */
    private val contract: String by lazy {
        CONTRACT
            .replace(
                "%SLACK%",
                HostHelpers.slack(
                    "this plugin was not granted SLACK_READ_THREAD",
                    "this plugin was not granted SLACK_POST_MESSAGE",
                    "this plugin was not granted SLACK_ADD_REACTION",
                    "this plugin was not granted SLACK_READ_MESSAGE",
                    "this plugin was not granted SLACK_READ_USER",
                    "this plugin was not granted SLACK_MENTION",
                    "this plugin was not granted SLACK_SEARCH",
                    "this plugin was not granted SLACK_SUGGEST",
                ).prependIndent("  "),
            )
            .replace("%HTTP%", HostHelpers.http("this plugin was not granted NETWORK_REQUEST").prependIndent("  "))
            .replace("%LOG%", HostHelpers.log(HostHelpers.threshold(properties.logLevel)).prependIndent("  "))
            .replace("%STORE%", HostHelpers.sessionStore().prependIndent("  "))
            .replace("%CRYPTO%", HostHelpers.crypto().prependIndent("  "))
            .replace("%ENCODING%", HostHelpers.encoding().prependIndent("  "))
            .replace(
                "%RENDER%",
                HostHelpers.render(
                    "this plugin was not granted RENDER_PNG",
                    "this plugin was not granted RENDER_PDF",
                ).prependIndent("  "),
            )
    }

    /**
     * Its own engine, so plugin sources — which are bundles, and large — do not
     * evict the parsed functions a workspace runs all day from a shared cache.
     */
    private val engine: Engine = Engine.newBuilder("js")
        .option("engine.WarnInterpreterOnly", "false")
        .build()

    /**
     * Stops a run that outstays its time, its heap, or its turn. Its own, with
     * its own bounds: a plugin is bigger than a function and is given longer and
     * more room, and the two must not be able to borrow each other's.
     */
    private val guard = ScriptGuard(
        "plugin",
        Bounds(
            timeoutMillis = properties.timeoutMillis,
            heapPressurePercent = properties.heapPressurePercent,
            suspectAfterBytes = properties.suspectAfterBytes,
            concurrency = properties.concurrency,
            queueMillis = properties.queueMillis,
        ),
    )

    /**
     * Everything the server needs to know about a plugin, in one evaluation.
     *
     * Asked together because the answers come from one object: the plugin is
     * constructed once and then questioned, which is both cheaper than loading it
     * three times and the only way the three answers are guaranteed to come from
     * the same instance.
     */
    fun inspect(
        source: String,
        libraries: List<PluginLibraryFile> = emptyList(),
        /**
         * How long the load may take, where the caller knows better than the
         * file - an installation's own answer, set on the admin screen. Null
         * is the file's number.
         */
        timeoutMillis: Long? = null,
    ): PluginInspection {
        val stopped = AtomicReference<Overrun?>(null)
        return try {
            /*
             * Read with nothing relaxed, always, and there is no parameter to say
             * otherwise. This is the call that finds out what the plugin is asking
             * for, and running it under permissions would mean granting something
             * in order to discover whether it should be granted.
             *
             * What it costs a plugin author is that the module body has to load
             * without the permissions the plugin needs - a bundle that touches
             * Intl at the top level cannot be loaded here. That is the safe
             * direction to be wrong in, and the template says so.
             */
            guard.bounded(stopped, { newContext(emptySet()) }, timeoutMillis) { read(it, source, libraries) }
        } catch (failure: PolyglotException) {
            PluginInspection.Unreadable(describe(failure, stopped = stopped.get(), timeoutMillis = timeoutMillis))
        } catch (failure: ScriptBusyException) {
            PluginInspection.Unreadable(failure.message ?: "could not be loaded")
        } catch (failure: IllegalStateException) {
            // Closing a cancelled context races with the call that was inside it.
            val overrun = guard.overrunReason(stopped.get(), timeoutMillis)
            PluginInspection.Unreadable(overrun?.plus(" while loading") ?: failure.message ?: "could not be loaded")
        }
    }

    /**
     * Runs one function the plugin declared.
     *
     * @param arguments JSON for each argument, in the order the function declares
     *   them, exactly as [ScriptRunner.call] takes them.
     * @param settings what this workspace set the plugin's parameters to, as a JSON
     *   object of name to value. It arrives frozen as `this.settings`, and it is
     *   the only thing a plugin is told about the workspace it is running for.
     * @param permissions what a person accepted for **this** plugin. Nothing else
     *   is relaxed, and nothing is relaxed for any other plugin: the context is
     *   built here, per call, from this set. Empty is the default and the answer
     *   for every plugin nobody has accepted anything for.
     *
     * The plugin is constructed for the call and thrown away with the context, so
     * one run cannot leave anything behind for the next — including the settings,
     * which differ per workspace and must not survive into another one's run.
     */
    fun call(
        source: String,
        functionName: String,
        arguments: List<String>,
        settings: String = "{}",
        permissions: Set<PluginPermission> = emptySet(),
        capabilities: Set<PluginCapability> = emptySet(),
        /**
         * Which workspace this run belongs to, which is the only one whose
         * connections a capability may reach. Taken from the run rather than
         * from the plugin, so nothing a plugin can write changes it.
         */
        on: Long? = null,
        /**
         * Which declaration list [functionName] lives in: `functions` for a
         * workflow's call, `tools` for an agent's. Two lists on purpose - the
         * two surfaces have different readers - so the lookup has to say which
         * it means.
         */
        surface: String = "functions",
        /**
         * The AI session this call is made inside, or null for one made
         * inside none - a tool tried from its page. It is what scopes
         * `orknux.session.store`: without it the store's doors are simply not
         * bound, and the helper says so.
         */
        sessionId: Long? = null,
        /**
         * The library files this plugin ships with, exactly as they were
         * accepted at load. Evaluated ahead of the plugin, each into the
         * registry its imports were rewritten to read from.
         */
        libraries: List<PluginLibraryFile> = emptyList(),
        /**
         * How long this one call may take, where the caller knows better than
         * the installation's plugin bound - a tool or a function with a
         * timeout of its own, or the workspace's default for its kind.
         *
         * Null is the installation's number, which is what every call used
         * before this existed: a plugin's tool ran under the bound meant for
         * *loading* a plugin, so a workspace that had set a two-minute tool
         * timeout was stopped at ten seconds and told so in a sentence naming
         * a number nobody had configured.
         */
        timeoutMillis: Long? = null,
    ): ScriptResult {
        val started = System.nanoTime()
        val stopped = AtomicReference<Overrun?>(null)
        return try {
            guard.bounded(stopped, { newContext(permissions) }, timeoutMillis) {
                ScriptResult.Returned(
                    invoke(it, source, functionName, arguments, settings, capabilities, on, surface, sessionId, libraries),
                    millis(started),
                )
            }
        } catch (failure: PolyglotException) {
            ScriptResult.Failed(
                describe(failure, doing = "running", stopped = stopped.get(), timeoutMillis = timeoutMillis),
                millis(started),
                settled = !(failure.isCancelled || failure.isResourceExhausted) && stopped.get() == null,
            )
        } catch (failure: ScriptBusyException) {
            ScriptResult.Failed(failure.message ?: "could not be run", millis(started), settled = false)
        } catch (failure: ScriptContractException) {
            ScriptResult.Failed(failure.message ?: "did not return", millis(started))
        } catch (failure: IllegalStateException) {
            val overrun = guard.overrunReason(stopped.get(), timeoutMillis)
            if (overrun != null) {
                ScriptResult.Failed("$overrun while running", millis(started), settled = false)
            } else {
                ScriptResult.Failed(failure.message ?: "could not be run", millis(started))
            }
        }
    }

    private fun invoke(
        polyglot: Context,
        source: String,
        functionName: String,
        arguments: List<String>,
        settings: String,
        capabilities: Set<PluginCapability>,
        on: Long?,
        surface: String,
        sessionId: Long?,
        libraries: List<PluginLibraryFile>,
    ): String? {
        polyglot.eval("js", contract)

        val bindings = polyglot.getBindings("js")
        // Put in before the plugin is constructed: the contract's helper reads it
        // while it is defining `settings` on the instance.
        bindings.putMember(SETTINGS, settings)

        val exported = evaluated(polyglot, source, libraries).getMember("default")
            ?: throw ScriptContractException("$functionName's plugin has no default export")

        bindings.putMember(PLUGIN, bindings.getMember(CONSTRUCT).execute(exported))
        bindings.putMember(WANTED, functionName)
        bindings.putMember(SURFACE, surface)
        bindings.putMember(ARGUMENTS, "[${arguments.joinToString(",")}]")
        // As text, like everything else that crosses, so the harness stays one
        // cached source rather than being respliced per call.
        bindings.putMember(RESULT_LIMIT, properties.resultLimitChars.toString())
        bind(bindings, capabilities, on, functionName, sessionId)
        polyglot.eval("js", CALL)

        val error = bindings.getMember(ERROR)
        if (error != null && !error.isNull) throw ScriptContractException(error.asString())

        val result = bindings.getMember(RESULT)
        return if (result == null || result.isNull) null else result.asString()
    }

    /** One string argument, or null where it was not one. */
    private fun text(given: Array<Value>, at: Int): String? =
        given.getOrNull(at)?.takeIf { it.isString }?.asString()

    /** One whole number, or null where it was neither whole nor a number. */
    private fun number(given: Array<Value>, at: Int): Int? =
        given.getOrNull(at)?.takeIf { it.isNumber && it.fitsInInt() }?.asInt()

    /**
     * The bytes a `(shape, value)` pair at [at] names.
     *
     * The helper says which shape it is sending because only it knows: `text`
     * is encoded here as UTF-8, and `base64` is decoded. Null where the pair
     * is not a pair, or where what claimed to be base64 is not.
     */
    private fun bytes(given: Array<Value>, at: Int): ByteArray? {
        val shape = text(given, at) ?: return null
        val held = text(given, at + 1) ?: return null
        return when (shape) {
            "text" -> held.toByteArray()
            "base64" -> PluginCrypto.decoded(held)
            else -> null
        }
    }

    /** An answer or a refusal, in the two shapes the helper splits on. */
    private fun said(result: PluginCrypto.Result): String = when (result) {
        is PluginCrypto.Result.Answer -> "ok:${PluginCrypto.encoded(result.bytes)}"
        is PluginCrypto.Result.Refused -> "no:${result.why}"
    }

    /** A member that is an array of strings, or null where there is none. */
    private fun strings(one: Value, named: String): List<String>? {
        val held = one.getMember(named)?.takeIf { it.hasArrayElements() } ?: return null
        return (0 until held.arraySize)
            .map { held.getArrayElement(it) }
            .filter { it.isString }
            .map { it.asString() }
            .takeIf { it.isNotEmpty() }
    }

    private fun millis(started: Long): Long = (System.nanoTime() - started) / 1_000_000

    private fun read(polyglot: Context, source: String, libraries: List<PluginLibraryFile> = emptyList()): PluginInspection {
        // The contract first: the plugin is evaluated against a sandbox that already
        // has OrknuxPlugin in it, so `extends OrknuxPlugin` resolves.
        polyglot.eval("js", contract)

        val exported = try {
            evaluated(polyglot, source, libraries).getMember("default")
        } catch (refused: ScriptContractException) {
            return PluginInspection.Unreadable(refused.message ?: "its files do not hold together")
        } ?: return PluginInspection.Unreadable("it has no default export")

        // Constructed by the contract's own helper, which is what refuses anything
        // that is not an OrknuxPlugin — and does it by prototype, not by shape.
        val plugin = polyglot.getBindings("js").getMember(CONSTRUCT).execute(exported)

        val id = plugin.invokeMember("id")
        if (!id.isString || id.asString().isBlank()) {
            return PluginInspection.Unreadable("id() did not answer with a name")
        }

        val version = plugin.invokeMember("apiVersion")
        if (!version.isNumber || !version.fitsInInt()) {
            return PluginInspection.Unreadable("apiVersion() did not answer with a whole number")
        }

        val declared = plugin.invokeMember("functions")
        if (!declared.hasArrayElements()) return PluginInspection.Unreadable("functions() did not answer with an array")
        if (declared.arraySize > MAX_FUNCTIONS) {
            return PluginInspection.Unreadable("functions() declared more than $MAX_FUNCTIONS functions")
        }

        val functions = (0 until declared.arraySize).map { at ->
            val one = declared.getArrayElement(at)
            // Every element passed through OrknuxFunction, which has already refused
            // anything without a name, a return type or something to run.
            val params = one.getMember("params")
            val read = (0 until (params?.arraySize ?: 0)).map { index ->
                val param = params.getArrayElement(index)
                DeclaredParam(
                    name = text(param, "name") ?: return PluginInspection.Unreadable("a parameter has no name"),
                    type = text(param, "type") ?: return PluginInspection.Unreadable("a parameter has no type"),
                    required = flag(param, "required", default = true),
                    // Already JSON: the contract stringifies it where it was
                    // written, so what is stored is what will be passed.
                    default = text(param, "defaultJson"),
                )
            }
            DeclaredFunction(
                name = text(one, "name") ?: return PluginInspection.Unreadable("a function has no name"),
                description = text(one, "description"),
                params = read,
                returnType = text(one, "returnType") ?: return PluginInspection.Unreadable("a function has no returnType"),
                source = text(one, "runSource"),
            )
        }

        /*
         * What it offers to agents. The same reading as the functions above,
         * with one extra shape: a tool constructed as an OrknuxFunctionTool
         * carries `proxyOf` instead of its own params, return type and run,
         * and those are resolved here against what functions() just declared -
         * so a proxy to a function the plugin does not have is refused at
         * load, not discovered by the first agent to call it.
         */
        val declaredTools = plugin.invokeMember("tools")
        if (!declaredTools.hasArrayElements()) {
            return PluginInspection.Unreadable("tools() did not answer with an array")
        }
        if (declaredTools.arraySize > MAX_FUNCTIONS) {
            return PluginInspection.Unreadable("tools() declared more than $MAX_FUNCTIONS tools")
        }

        val tools = (0 until declaredTools.arraySize).map { at ->
            val one = declaredTools.getArrayElement(at)
            val proxyOf = text(one, "proxyOf")
            if (proxyOf != null) {
                val target = functions.firstOrNull { it.name == proxyOf }
                    ?: return PluginInspection.Unreadable(
                        "tools() proxies \"$proxyOf\", which functions() does not declare",
                    )
                DeclaredTool(
                    name = text(one, "name") ?: proxyOf,
                    description = text(one, "description") ?: target.description,
                    params = target.params,
                    returnType = target.returnType,
                    proxyOf = proxyOf,
                )
            } else {
                val params = one.getMember("params")
                val read = (0 until (params?.arraySize ?: 0)).map { index ->
                    val param = params.getArrayElement(index)
                    DeclaredParam(
                        name = text(param, "name") ?: return PluginInspection.Unreadable("a tool parameter has no name"),
                        type = text(param, "type") ?: return PluginInspection.Unreadable("a tool parameter has no type"),
                        required = flag(param, "required", default = true),
                        default = text(param, "defaultJson"),
                    )
                }
                DeclaredTool(
                    name = text(one, "name") ?: return PluginInspection.Unreadable("a tool has no name"),
                    description = text(one, "description"),
                    params = read,
                    returnType = text(one, "returnType")
                        ?: return PluginInspection.Unreadable("a tool has no returnType"),
                    proxyOf = null,
                )
            }
        }

        /*
         * What the plugin needs to be told before it can do anything. Read here
         * rather than discovered on the first call, because the point of declaring
         * them is that a workspace can be shown what a plugin will be given before
         * it is given anything.
         */
        val wanted = plugin.invokeMember("parameters")
        if (!wanted.hasArrayElements()) {
            return PluginInspection.Unreadable("parameters() did not answer with an array")
        }
        if (wanted.arraySize > MAX_PARAMETERS) {
            return PluginInspection.Unreadable("parameters() declared more than $MAX_PARAMETERS parameters")
        }

        val parameters = (0 until wanted.arraySize).map { at ->
            val one = wanted.getArrayElement(at)
            // Every element passed through OrknuxParameter, which has already
            // refused anything without a name or a type.
            DeclaredParameter(
                name = text(one, "name") ?: return PluginInspection.Unreadable("a parameter has no name"),
                description = text(one, "description"),
                type = text(one, "type") ?: return PluginInspection.Unreadable("a parameter has no type"),
                required = flag(one, "required", default = true),
                secret = flag(one, "secret", default = false),
                connectionType = text(one, "connectionType"),
                options = strings(one, "options"),
            )
        }

        /*
         * What JavaScript it says it needs. Read here, before anything is stored
         * and before anything is granted, because the whole arrangement is that a
         * person is shown this list and agrees to it — and a list discovered on the
         * first call would be a list nobody was ever shown.
         */
        val wantedCapabilities = if (plugin.hasMember("capabilities")) {
            val declaredCapabilities = plugin.invokeMember("capabilities")
            if (!declaredCapabilities.hasArrayElements()) {
                return PluginInspection.Unreadable("capabilities() did not answer with an array")
            }
            (0 until declaredCapabilities.arraySize).map { at ->
                val one = declaredCapabilities.getArrayElement(at)
                if (!one.isString) {
                    return PluginInspection.Unreadable("capabilities() answered with something that is not a name")
                }
                one.asString()
            }
        } else {
            emptyList()
        }

        val asked = plugin.invokeMember("permissions")
        if (!asked.hasArrayElements()) {
            return PluginInspection.Unreadable("permissions() did not answer with an array")
        }
        if (asked.arraySize > MAX_PERMISSIONS) {
            return PluginInspection.Unreadable("permissions() asked for more than $MAX_PERMISSIONS things")
        }
        val permissions = (0 until asked.arraySize).map { at ->
            val one = asked.getArrayElement(at)
            if (!one.isString) return PluginInspection.Unreadable("permissions() answered with something that is not a name")
            one.asString().trim()
        }.filter { it.isNotEmpty() }.distinct()

        /*
         * The library files it says it ships with. Only the shape is judged
         * here - relative, /-joined, ending in .js - because the sandbox knows
         * nothing about what actually arrived beside the plugin; whether the
         * declaration and the files agree is the loader's question, asked
         * against the zip's entries or the fetched set.
         */
        val shipped = if (plugin.hasMember("libraries")) {
            val declaredLibraries = plugin.invokeMember("libraries")
            if (!declaredLibraries.hasArrayElements()) {
                return PluginInspection.Unreadable("libraries() did not answer with an array")
            }
            if (declaredLibraries.arraySize > MAX_LIBRARIES) {
                return PluginInspection.Unreadable("libraries() declared more than $MAX_LIBRARIES files")
            }
            val paths = (0 until declaredLibraries.arraySize).map { at ->
                val one = declaredLibraries.getArrayElement(at)
                if (!one.isString) {
                    return PluginInspection.Unreadable("libraries() answered with something that is not a path")
                }
                val held = one.asString().trim()
                if (held.length > MOST_LIBRARY_PATH_CHARS) {
                    return PluginInspection.Unreadable(
                        "a library path is at most $MOST_LIBRARY_PATH_CHARS characters, and one is ${held.length}",
                    )
                }
                if (!LIBRARY_PATH.matches(held)) {
                    return PluginInspection.Unreadable(
                        "\"$held\" is not a usable library path: relative, /-joined and ending in .js - " +
                            "no absolute paths, no URLs, no .., no bare specifiers",
                    )
                }
                // The same file spelled with and without './' is one file,
                // stored without.
                held.removePrefix("./")
            }
            if (paths.size != paths.distinct().size) {
                return PluginInspection.Unreadable("libraries() declares the same file more than once")
            }
            paths
        } else {
            emptyList()
        }

        /*
         * The instructions it brings. Read here with the rest because they
         * are part of what the plugin is rather than something fetched later
         * - and because whoever loads it should see the whole of what arrives
         * in one place. Only the shape is judged: that each has a name and a
         * body, and that neither is longer than a row can hold. Whether the
         * body is frontmattered the way a skill must be is the server's
         * question, since the server is what knows that format.
         */
        val taught = if (plugin.hasMember("skills")) {
            val declaredSkills = plugin.invokeMember("skills")
            if (!declaredSkills.hasArrayElements()) {
                return PluginInspection.Unreadable("skills() did not answer with an array")
            }
            if (declaredSkills.arraySize > MAX_SKILLS) {
                return PluginInspection.Unreadable("skills() declared more than $MAX_SKILLS skills")
            }
            (0 until declaredSkills.arraySize).map { at ->
                val one = declaredSkills.getArrayElement(at)
                // Every element passed through OrknuxSkill, which has already
                // refused anything without a name or a body.
                val name = text(one, "name") ?: return PluginInspection.Unreadable("a skill has no name")
                val content = text(one, "content")
                    ?: return PluginInspection.Unreadable("the skill $name has no content")
                if (content.length > MOST_SKILL_CHARS) {
                    return PluginInspection.Unreadable(
                        "the skill $name is ${content.length} characters, and a skill is at most $MOST_SKILL_CHARS",
                    )
                }
                DeclaredSkill(name = name.trim(), description = text(one, "description"), content = content)
            }
        } else {
            emptyList()
        }

        /*
         * The shapes it exports. Only what one field says about itself is
         * judged here - the contract's own constructor has already refused a
         * kind that is not a kind and an `of` where none belongs. Whether an
         * `of` names an object this plugin actually declares needs the whole
         * set, and that is the server's question, along with turning the
         * names into references.
         */
        val shapes = if (plugin.hasMember("objects")) {
            val declaredObjects = plugin.invokeMember("objects")
            if (!declaredObjects.hasArrayElements()) {
                return PluginInspection.Unreadable("objects() did not answer with an array")
            }
            if (declaredObjects.arraySize > MAX_OBJECTS) {
                return PluginInspection.Unreadable("objects() declared more than $MAX_OBJECTS objects")
            }
            (0 until declaredObjects.arraySize).map { at ->
                val one = declaredObjects.getArrayElement(at)
                val name = text(one, "name") ?: return PluginInspection.Unreadable("an object has no name")
                val properties = one.getMember("properties")
                if (properties != null && properties.arraySize > MAX_PROPERTIES) {
                    return PluginInspection.Unreadable("$name declares more than $MAX_PROPERTIES properties")
                }
                val fields = (0 until (properties?.arraySize ?: 0)).map { index ->
                    val held = properties.getArrayElement(index)
                    DeclaredProperty(
                        name = text(held, "name")
                            ?: return PluginInspection.Unreadable("$name has a property with no name"),
                        kind = text(held, "kind")
                            ?: return PluginInspection.Unreadable("$name has a property with no kind"),
                        of = text(held, "of"),
                        description = text(held, "description"),
                    )
                }
                DeclaredObject(name = name.trim(), description = text(one, "description"), properties = fields)
            }
        } else {
            emptyList()
        }

        /*
         * The value types it defines. Issue #377.
         *
         * A plugin's type is a base type - string, number, boolean - with a
         * name, and optionally a way to complete and a way to check a value.
         * Whether it can do either is read here so the screen knows what to
         * offer before anything is typed; the doing is a call through
         * [call] on the `types:suggest` or `types:validate` surface.
         */
        val kinds = if (plugin.hasMember("types")) {
            val declaredTypes = plugin.invokeMember("types")
            if (!declaredTypes.hasArrayElements()) {
                return PluginInspection.Unreadable("types() did not answer with an array")
            }
            if (declaredTypes.arraySize > MAX_TYPES) {
                return PluginInspection.Unreadable("types() declared more than $MAX_TYPES types")
            }
            (0 until declaredTypes.arraySize).map { at ->
                val one = declaredTypes.getArrayElement(at)
                val name = text(one, "name") ?: return PluginInspection.Unreadable("a type has no name")
                val asked = one.getMember("parameters")?.takeIf { it.hasArrayElements() }
                if (asked != null && asked.arraySize > MAX_TYPE_PARAMETERS) {
                    return PluginInspection.Unreadable("$name declares more than $MAX_TYPE_PARAMETERS parameters")
                }
                val parameters = (0 until (asked?.arraySize ?: 0)).map { index ->
                    val held = asked!!.getArrayElement(index)
                    DeclaredParameter(
                        name = text(held, "name")
                            ?: return PluginInspection.Unreadable("$name has a parameter with no name"),
                        description = text(held, "description"),
                        type = text(held, "type")
                            ?: return PluginInspection.Unreadable("$name has a parameter with no type"),
                        required = flag(held, "required", default = true),
                        secret = flag(held, "secret", default = false),
                        connectionType = text(held, "connectionType"),
                        options = strings(held, "options"),
                    )
                }
                DeclaredType(
                    name = name.trim(),
                    description = text(one, "description"),
                    base = text(one, "base") ?: return PluginInspection.Unreadable("$name has no base type"),
                    parameters = parameters,
                    suggests = one.getMember("suggest")?.canExecute() == true,
                    validates = one.getMember("validate")?.canExecute() == true,
                )
            }
        } else {
            emptyList()
        }

        return PluginInspection.Read(
            id = id.asString().trim(),
            apiVersion = version.asInt(),
            functions = functions,
            tools = tools,
            parameters = parameters,
            permissions = permissions,
            capabilities = wantedCapabilities,
            libraries = shipped,
            skills = taught,
            objects = shapes,
            types = kinds,
        )
    }

    /** A member that has to be a boolean to be worth reading. */
    private fun flag(holder: Value, member: String, default: Boolean): Boolean {
        val value = holder.getMember(member) ?: return default
        if (!value.isBoolean) return default
        return value.asBoolean()
    }

    /** A member that has to be a string to be worth reading. */
    private fun text(holder: Value, member: String): String? {
        val value = holder.getMember(member) ?: return null
        if (!value.isString) return null
        return value.asString().trim().ifEmpty { null }
    }

    /**
     * What went wrong, said in terms of what the sandbox was doing at the time.
     *
     * [doing] is the difference between "took too long to load" and "took too long
     * to run", and whoever reads the sentence needs to know which of the two they
     * are looking at.
     */
    private fun describe(
        failure: PolyglotException,
        doing: String = "loading",
        stopped: Overrun? = null,
        /**
         * The bound that actually applied, where this call had one of its own.
         *
         * Said rather than assumed: the sentence used to name the
         * installation's plugin timeout whatever the run was given, so a tool
         * stopped at its workspace's two minutes reported ten seconds - a
         * number nobody had set, about a wait nobody had had.
         */
        timeoutMillis: Long? = null,
    ): String = when {
        // A cancelled context says only that somebody stopped it; the guard is
        // the one who knows whether that was the clock or the heap.
        stopped != null -> "${guard.overrunReason(stopped, timeoutMillis)} while $doing"
        failure.isCancelled -> "took longer than ${timeoutMillis ?: properties.timeoutMillis} ms while $doing"
        failure.isResourceExhausted -> exhausted(failure, doing)
        // A guest exception here is usually the contract refusing something, and its
        // message says what — so it is passed on rather than summarised.
        failure.isGuestException -> failure.message ?: "threw while $doing"
        else -> failure.message ?: "could not be run"
    }

    /**
     * Which budget it was that ran out.
     *
     * `isResourceExhausted` covers both the statement limit and a guest heap that
     * could not grow. They read the same to the flag and mean opposite things to
     * whoever has to fix the plugin, so the message is taken from what the
     * failure said: the heap one arrives as "Java heap space".
     */
    private fun exhausted(failure: PolyglotException, doing: String): String {
        val said = failure.message ?: ""
        val memory = said.contains("heap space", ignoreCase = true) ||
            said.contains("out of memory", ignoreCase = true)
        return if (memory) {
            "asked for more memory than it was given while $doing"
        } else {
            "ran more than ${properties.statementLimit} statements while $doing"
        }
    }

    /**
     * No host access, and this time none of it.
     *
     * `HostAccess.NONE` denies every host method and field and then stops, one
     * short of the default mappings of guest values onto mutable host types: a
     * guest array handed to host code that asks it for a `List` still becomes
     * one, and what backs it is whatever the guest felt like. Today nothing
     * asks — a plugin's answers are read out a string, a number, an array
     * element at a time, never converted wholesale — but "a plugin will one day
     * be given authority a function must not have" is the premise of this class,
     * and a mapping like this is exactly what would be found already switched on
     * when that day came.
     *
     * Its own copy, like every other line here. A constant shared with
     * [ScriptRunner] would be one place that configures both, which is what this
     * file exists to avoid.
     */
    internal val hostAccess: HostAccess = HostAccess.newBuilder(HostAccess.NONE)
        // Reads backwards: the argument lists the mappings to allow, so the
        // empty call denies them all. Omitted, the builder allows every one.
        .allowMutableTargetMappings()
        .build()

    /**
     * The sandbox. Every `allow…` is a decision to say no, written out even where
     * the builder would have denied it anyway — so the day a plugin is given a
     * capability, it is a visible line in this file and not a default that moved.
     */
    /**
     * The sandbox, built for one plugin and one call.
     *
     * Every `allow…` is a decision to say no, written out even where the builder
     * would have denied it anyway — so the day a plugin is given a capability, it
     * is a visible line in this file and not a default that moved.
     *
     * [permissions] is the only thing that varies, it varies per plugin, and it can
     * only ever add a language builtin: see [PluginPermission] for why the
     * vocabulary cannot express anything else. Two options are turned *off* here
     * that GraalJS has on by default — `js.console` and `js.intl-402` — because
     * "nothing is relaxed unless it was accepted" is not true of a default that
     * happened to be on, and a plugin that wants either now has to say so.
     *
     * The options are applied last and only from the enumeration, so nothing a
     * plugin wrote reaches this builder as text.
     */
    /**
     * The `orknux` object, holding exactly what this plugin was granted.
     *
     * A [ProxyExecutable] rather than a Java object: a proxy is a polyglot value
     * the engine calls directly, so nothing here is host reflection and none of
     * the denials in [newContext] has to be relaxed for it. A plugin granted
     * nothing gets no host behind the `orknux` helpers, so they refuse in words
     * rather than reaching anything. The helpers themselves are part of the
     * contract and always there: a plugin calling one ungranted should be told
     * so, not thrown whatever a call on undefined throws.
     *
     * Everything crosses as JSON text, both ways. A plugin handed a live object
     * could walk from it to a class loader; a plugin handed a string can read
     * the string.
     */
    private fun bind(bindings: Value, capabilities: Set<PluginCapability>, on: Long?, named: String, sessionId: Long? = null) {
        /*
         * The logging door first, and outside the guard below: it is not a
         * capability and never needed granting - nothing is reached by it - so a
         * plugin that declared nothing can still say what it is doing, which is
         * the plugin somebody is most often trying to debug.
         */
        bindings.putMember(
            LOG,
            ProxyExecutable { given ->
                val level = given.getOrNull(0)?.takeIf { it.isString }?.asString() ?: "info"
                val line = given.getOrNull(1)?.takeIf { it.isString }?.asString() ?: return@ProxyExecutable null
                val about = "[workspace ${on ?: "?"}] $named: $line"
                when (level) {
                    "debug" -> pluginLog.debug(about)
                    "warn" -> pluginLog.warn(about)
                    "error" -> pluginLog.error(about)
                    else -> pluginLog.info(about)
                }
                null
            },
        )

        /*
         * The crypto doors, beside the log's and granted the same way, which
         * is to say not at all: they reach nothing and only compute.
         *
         * Without them a plugin cannot hash anything whatsoever - the engine
         * has no crypto of its own and no option to turn one on - so a
         * database handshake is out of reach before it begins, and a plugin
         * verifying a signature is reduced to `===`.
         *
         * Each answers `ok:<base64>` or `no:<sentence>`; a refusal is data
         * the plugin can act on rather than a throw it has to catch.
         */
        bindings.putMember(
            CRYPTO_HASH,
            ProxyExecutable { given ->
                val algorithm = text(given, 0) ?: return@ProxyExecutable "no:an algorithm has to be named"
                val input = bytes(given, 1) ?: return@ProxyExecutable "no:input has to be given"
                said(PluginCrypto.hash(algorithm, input))
            },
        )
        bindings.putMember(
            CRYPTO_HMAC,
            ProxyExecutable { given ->
                val algorithm = text(given, 0) ?: return@ProxyExecutable "no:an algorithm has to be named"
                val key = bytes(given, 1) ?: return@ProxyExecutable "no:a key has to be given"
                val input = bytes(given, 3) ?: return@ProxyExecutable "no:input has to be given"
                said(PluginCrypto.hmac(algorithm, key, input))
            },
        )
        bindings.putMember(
            CRYPTO_PBKDF2,
            ProxyExecutable { given ->
                val algorithm = text(given, 0) ?: return@ProxyExecutable "no:an algorithm has to be named"
                val password = bytes(given, 1) ?: return@ProxyExecutable "no:a password has to be given"
                val salt = bytes(given, 3) ?: return@ProxyExecutable "no:a salt has to be given"
                val iterations = number(given, 5) ?: return@ProxyExecutable "no:iterations has to be a number"
                val length = number(given, 6) ?: return@ProxyExecutable "no:a length has to be a number"
                said(PluginCrypto.pbkdf2(algorithm, password, salt, iterations, length))
            },
        )
        bindings.putMember(
            CRYPTO_RANDOM,
            ProxyExecutable { given ->
                val wanted = number(given, 0) ?: return@ProxyExecutable "no:a count has to be a number"
                said(PluginCrypto.random(wanted))
            },
        )
        bindings.putMember(
            CRYPTO_EQUAL,
            ProxyExecutable { given ->
                val left = bytes(given, 0) ?: return@ProxyExecutable "no:both sides have to be given"
                val right = bytes(given, 2) ?: return@ProxyExecutable "no:both sides have to be given"
                "ok:${PluginCrypto.equal(left, right)}"
            },
        )

        /*
         * And the two that are only a change of clothes. They are here rather
         * than left to the plugin because without them the rest is out of
         * reach: the sandbox has no TextEncoder unless TEXT_ENCODING was
         * granted, so a plugin holding a string cannot make the bytes the
         * doors above take, nor read the bytes they answer with.
         */
        bindings.putMember(
            CRYPTO_ENCODE,
            ProxyExecutable { given ->
                val input = bytes(given, 0) ?: return@ProxyExecutable "no:input has to be given"
                "ok:${PluginCrypto.encoded(input)}"
            },
        )
        bindings.putMember(
            CRYPTO_DECODE,
            ProxyExecutable { given ->
                val held = text(given, 0) ?: return@ProxyExecutable "no:base64 has to be a string"
                val raw = PluginCrypto.decoded(held) ?: return@ProxyExecutable "no:that is not base64"
                when (val said = PluginCrypto.text(raw)) {
                    is PluginCrypto.Result.Answer -> "ok:${String(said.bytes)}"
                    is PluginCrypto.Result.Refused -> "no:${said.why}"
                }
            },
        )

        /*
         * The store's doors, beside the log's and like it not a capability:
         * nothing outside the session is reached by them. Bound only where
         * the call was made inside an AI session, so a tool tried from its
         * page is told there is no store here rather than writing into
         * nowhere.
         */
        val store = scratch
        if (store != null && sessionId != null) {
            bindings.putMember(
                STORE_PUT,
                ProxyExecutable { given ->
                    val key = given.getOrNull(0)?.takeIf { it.isString }?.asString()
                        ?: return@ProxyExecutable "a key has to be a string"
                    val value = given.getOrNull(1)?.takeIf { it.isString }?.asString()
                        ?: return@ProxyExecutable "a value has to be given"
                    store.put(sessionId, key, value)
                },
            )
            bindings.putMember(
                STORE_GET,
                ProxyExecutable { given ->
                    val key = given.getOrNull(0)?.takeIf { it.isString }?.asString()
                        ?: return@ProxyExecutable null
                    store.get(sessionId, key)
                },
            )
        }

        val server = host
        if (capabilities.isEmpty() || server == null) return

        val granted = LinkedHashMap<String, Any>()
        capabilities.forEach { capability ->
            granted[capability.name.lowercase()] = ProxyExecutable { given ->
                /*
                 * One argument, already JSON, because the contract hands it that
                 * way. A plugin calling this by hand with something else is
                 * refused rather than guessed at: the host is about to make a
                 * call on a workspace's behalf, and inventing what was meant is
                 * the wrong instinct there.
                 */
                val argument = given.firstOrNull()?.takeIf { it.isString }?.asString()
                    ?: return@ProxyExecutable REFUSED
                server.ask(capability, argument, on)
            }
        }
        bindings.putMember(HOST, ProxyObject.fromMap(granted))
    }

    private fun newContext(permissions: Set<PluginPermission>): Context = Context.newBuilder("js")
        .engine(engine)
        .allowExperimentalOptions(true)
        .allowHostAccess(hostAccess)
        .allowHostClassLookup { false }
        .allowHostClassLoading(false)
        .allowIO(IOAccess.NONE)
        .allowCreateThread(false)
        .allowCreateProcess(false)
        .allowNativeAccess(false)
        .allowPolyglotAccess(PolyglotAccess.NONE)
        .allowEnvironmentAccess(org.graalvm.polyglot.EnvironmentAccess.NONE)
        .allowValueSharing(false)
        .resourceLimits(
            ResourceLimits.newBuilder()
                .statementLimit(properties.statementLimit, null)
                .build(),
        )
        .option("js.load", "false")
        .option("js.print", "false")
        .option("js.java-package-globals", "false")
        .option("js.polyglot-builtin", "false")
        .option("js.graal-builtin", "false")
        .option("js.ecmascript-version", "2023")
        .option("js.esm-eval-returns-exports", "true")
        // Denied unless accepted, though GraalJS gives both away by default.
        .option("js.console", "false")
        .option("js.intl-402", "false")
        .granting(permissions)
        .build()

    /**
     * Turns on exactly what was accepted, and can turn on nothing else.
     *
     * The option name comes off the enumeration rather than out of anything the
     * plugin wrote, so no string a plugin controls reaches the builder.
     */
    private fun Context.Builder.granting(permissions: Set<PluginPermission>): Context.Builder =
        permissions.fold(this) { builder, granted -> builder.option(granted.option, "true") }

    private fun module(source: String, name: String = "plugin.mjs"): Source = Source.newBuilder("js", source, name)
        .mimeType("application/javascript+module")
        .buildLiteral()

    /**
     * The plugin's module, with its libraries evaluated ahead of it.
     *
     * A single-file plugin is evaluated as it always was. One that ships
     * libraries is a [PluginBundle]: the files are ordered by their import
     * graph, each is evaluated as a module of its own with its imports
     * rewritten into registry reads, its exports land in the registry under
     * its declared path, and the plugin - rewritten the same way - is
     * evaluated last. Nothing resolves a path inside the sandbox; the graph
     * was settled outside it.
     */
    private fun evaluated(polyglot: Context, source: String, libraries: List<PluginLibraryFile>): Value {
        if (libraries.isEmpty()) return polyglot.eval(module(source))

        val ordered = when (val bundle = PluginBundle.of(source, libraries)) {
            is PluginBundle.Ordered -> bundle.libraries
            is PluginBundle.Refused -> throw ScriptContractException(bundle.reason)
        }
        polyglot.eval("js", "globalThis.${PluginBundle.REGISTRY} = {};")
        val registry = polyglot.getBindings("js").getMember(PluginBundle.REGISTRY)
        for (library in ordered) {
            val exports = polyglot.eval(module(PluginBundle.rewritten(library.source, library.path), library.path))
            registry.putMember(library.path, exports)
        }
        return polyglot.eval(module(PluginBundle.rewritten(source)))
    }

    companion object {
        const val CONSTRUCT = "__orknuxConstruct"

        /** What the workspace set the plugin's parameters to, as JSON, on its way in. */
        const val SETTINGS = "__orknuxSettings"

        const val PLUGIN = "__orknuxPlugin"
        const val WANTED = "__orknuxWanted"
        const val ARGUMENTS = "__orknuxPluginArguments"
        const val RESULT = "__orknuxPluginResult"
        const val ERROR = "__orknuxPluginError"
        const val RESULT_LIMIT = "__orknuxPluginResultLimit"

        /** Which declaration list the wanted name is looked up in: functions or tools. */
        const val SURFACE = "__orknuxPluginSurface"

        /** What the granted capabilities are bound as, and what the contract calls them. */
        const val HOST = "__orknuxHost"

        /** Where `orknux.log` hands a line over. Not a capability; nothing is reached by it. */
        const val LOG = "__orknuxLog"

        /** The execution store's two doors; bound only inside a workflow execution. */
        /**
         * The crypto doors. Five rather than one taking a shape, because the
         * alternative is a JSON parser on the far side of a boundary that
         * exists to keep things simple.
         */
        const val CRYPTO_HASH = "__orknuxCryptoHash"
        const val CRYPTO_HMAC = "__orknuxCryptoHmac"
        const val CRYPTO_PBKDF2 = "__orknuxCryptoPbkdf2"
        const val CRYPTO_RANDOM = "__orknuxCryptoRandom"
        const val CRYPTO_EQUAL = "__orknuxCryptoEqual"
        const val CRYPTO_ENCODE = "__orknuxCryptoEncode"
        const val CRYPTO_DECODE = "__orknuxCryptoDecode"

        const val STORE_PUT = "__orknuxStorePut"
        const val STORE_GET = "__orknuxStoreGet"

        /**
         * The plugins' own logger, separate from this class's and from the
         * functions'. An installation debugging a plugin it was handed should
         * not have to turn up every function it wrote itself to do it.
         */
        val pluginLog: Logger = LoggerFactory.getLogger("io.mszymanski.orknux.plugin")

        /** Said when a capability is called with something other than JSON. */
        const val REFUSED = """{"error":"that call needs its arguments as JSON"}"""

        /** More than a plugin has any business offering, and a bound on the answer. */
        const val MAX_FUNCTIONS = 100

        /**
         * More than a plugin has any business asking for.
         *
         * Lower than the function bound on purpose: every one of these is something
         * a person has to sit down and fill in, and a plugin asking for fifty pieces
         * of configuration is asking the wrong question.
         */
        const val MAX_PARAMETERS = 50

        /**
         * More than there are permissions to ask for.
         *
         * A bound on the answer rather than a rule about plugins: what is actually
         * allowed is decided against [PluginPermission], and a plugin naming
         * something that is not on it is refused whatever the length of the list.
         */
        const val MAX_PERMISSIONS = 32

        /**
         * More library files than a plugin has any business shipping. Every
         * one is a file whoever loads the plugin has to allow, and a list too
         * long to read is a list nobody reads. `MAX_LIBRARIES` in the
         * @orknux/plugin package mirrors it.
         */
        const val MAX_LIBRARIES = 50

        /**
         * The shape of one library path: relative segments joined by `/`, an
         * optional leading `./`, ending in `.js`. What it rules out is the
         * point - nothing absolute, no URL, no `..`, no backslashes, no bare
         * specifier - so a declared path can only ever name a file that
         * travels with the plugin. `LIBRARY_PATH` in @orknux/plugin mirrors it.
         */
        val LIBRARY_PATH = Regex("""(\./)?(?!\.)[A-Za-z0-9_\-.]+(/(?!\.)[A-Za-z0-9_\-.]+)*\.js""")

        /** Longer than any sensible relative path; the column the server keeps one in. */
        const val MOST_LIBRARY_PATH_CHARS = 200

        /**
         * More instruction sets than one plugin has to teach. A plugin that
         * brings fifty skills is a workspace's skill catalog wearing a
         * plugin's clothes. `MAX_SKILLS` in @orknux/plugin mirrors it.
         */
        const val MAX_SKILLS = 25

        /**
         * A skill is a page, not a manual. Generous enough for a long one and
         * bounded, because this crosses out of a sandbox into a column.
         */
        const val MOST_SKILL_CHARS = 64 * 1024

        /**
         * More shapes than a plugin has any business exporting.
         *
         * Lower than the function bound: every one of these is a name that
         * lands in every workspace at once, and a plugin bringing a hundred
         * types is bringing a schema nobody asked for. `MAX_OBJECTS` in
         * @orknux/plugin mirrors it.
         */
        const val MAX_OBJECTS = 50

        /** Fields on one exported object; the same bound a workspace's own has. */
        const val MAX_PROPERTIES = 100

        /**
         * Value types a plugin may define. Issue #377.
         *
         * Lower still than objects: each one is an entry in the type picker of
         * every variable in every workspace, and a plugin that needs twenty
         * kinds of string is describing an API rather than a vocabulary.
         * `MAX_TYPES` in @orknux/plugin mirrors it.
         */
        const val MAX_TYPES = 20

        /** What one type may ask to be told; a connection and a couple of settings. */
        const val MAX_TYPE_PARAMETERS = 10

        /**
         * The same shape without the `.js`, for the files beside a plugin that
         * are not code — its manifest, its icon. Relative and contained, for
         * the reason [LIBRARY_PATH] is: a path that could climb out is a path
         * that could name something this plugin did not ship.
         */
        val LIBRARY_PATH_ANY = Regex("""(\./)?(?!\.)[A-Za-z0-9_\-.]+(/(?!\.)[A-Za-z0-9_\-.]+)*""")

        /**
         * Runs one of the plugin's declared functions and leaves JSON behind.
         *
         * The plugin is asked for its declarations again rather than the function
         * being looked up by name on the instance: what the plugin offers is what
         * `functions()` answers, and a method that happens to share a name with a
         * declaration is not the same thing as the declaration's `run`.
         *
         * Called with the plugin as `this`, so a `run` written as a method reaches
         * `this.settings`. One written as an arrow function inside `functions()`
         * already closes over the same instance, so both spellings see the same
         * parameters.
         */
        val CALL = """
            (function () {
              globalThis.$RESULT = null;
              globalThis.$ERROR = null;
              try {
                var plugin = globalThis.$PLUGIN;
                /*
                 * Which list is searched and which member is called. A function
                 * and a tool run; a type is asked to suggest or to validate,
                 * which are two doors on the one declaration.
                 */
                var surface = globalThis.$SURFACE;
                var declared, method;
                if (surface === 'tools') { declared = plugin.tools(); method = 'run'; }
                else if (surface === 'types:suggest') { declared = plugin.types(); method = 'suggest'; }
                else if (surface === 'types:validate') { declared = plugin.types(); method = 'validate'; }
                else { declared = plugin.functions(); method = 'run'; }
                var wanted = null;
                for (var at = 0; at < declared.length; at++) {
                  if (declared[at].name === globalThis.$WANTED) { wanted = declared[at]; break; }
                }
                if (wanted === null) {
                  globalThis.$ERROR = 'the plugin no longer declares ' + globalThis.$WANTED;
                  return;
                }
                if (typeof wanted[method] !== 'function') {
                  globalThis.$ERROR = globalThis.$WANTED + ' does not ' + method;
                  return;
                }
                var args = JSON.parse(globalThis.$ARGUMENTS);
                var limit = Number(globalThis.$RESULT_LIMIT);
                Promise.resolve(wanted[method].apply(plugin, args)).then(
                  function (value) {
                    var json = value === undefined ? null : JSON.stringify(value);
                    // Measured before it crosses. What a function answers with is
                    // written to the step, parsed into a tree, and handed to the
                    // next node; the cheap place to refuse an oversized one is
                    // here, where the string has only just been made.
                    if (json !== null && json.length > limit) {
                      globalThis.$ERROR = 'returned ' + json.length +
                        ' characters of JSON, more than the ' + limit + ' it is allowed';
                      return;
                    }
                    globalThis.$RESULT = json;
                  },
                  function (failure) {
                    globalThis.$ERROR = String((failure && failure.message) || failure);
                  }
                );
              } catch (failure) {
                globalThis.$ERROR = String((failure && failure.message) || failure);
              }
            })();
        """.trimIndent()

        /**
         * What a plugin extends, and what refuses anything that does not.
         *
         * Put in the sandbox before the plugin is evaluated, so `extends
         * OrknuxPlugin` resolves without the plugin importing anything — there is no
         * module resolution in here, and there should not be.
         *
         * The base methods throw rather than returning nothing. A plugin that has
         * not implemented one gets an error naming it, which is a better answer than
         * the server reporting that some key was absent.
         *
         * `OrknuxFunction` checks a declaration as it is constructed, so a function
         * missing a name or something to run fails where it was written rather than
         * on the way into the database.
         */
        val CONTRACT = """
            globalThis.OrknuxPlugin = class OrknuxPlugin {
              id() {
                throw new Error('a plugin must implement id(), answering what it is called');
              }

              apiVersion() {
                throw new Error('a plugin must implement apiVersion(), answering which plugin API it uses');
              }

              functions() {
                return [];
              }

              /**
               * What this plugin offers to agents, as tools a model calls.
               *
               * Separate from functions(), which workflows call: the two
               * surfaces have different readers, and a tool's description is
               * written for a model where a function's is written for a person
               * building a workflow. A tool that is really one of the
               * functions is declared as an OrknuxFunctionTool, which proxies
               * it rather than describing it twice.
               */
              tools() {
                return [];
              }

              parameters() {
                return [];
              }

              permissions() {
                return [];
              }

              /**
               * What this plugin asks the server to do on its behalf.
               *
               * Separate from permissions(), which only ever turns on a language
               * builtin. These reach outside - so they are declared apart,
               * granted apart, and shown apart to whoever accepts the plugin.
               */
              capabilities() {
                return [];
              }

              /**
               * The library files this plugin ships with, as paths relative
               * to its own file: 'lib/util.js' or './lib/util.js'. The
               * complete list - every file that arrives beside the plugin is
               * declared here, and every relative import resolves within it.
               * Whoever loads the plugin is shown the list and has to allow
               * it. Defaults to none, which is every single-file plugin.
               */
              libraries() {
                return [];
              }

              /**
               * The skills this plugin brings: instructions an agent can be
               * given, as OrknuxSkill objects.
               *
               * A third surface, and a third reader. functions() is called by
               * a workflow and tools() by a model; a skill is neither called
               * nor run - it is markdown an agent reads to learn how this
               * plugin's work is meant to be done. A plugin that offers a
               * Slack search tool can ship the skill that says when to reach
               * for it, and the two travel together instead of the second
               * being retyped into every workspace by hand.
               *
               * They arrive as a catalog named after the plugin, and an agent
               * is granted that catalog the way it is granted any other.
               * Nothing is automatic.
               */
              skills() {
                return [];
              }

              /**
               * The shapes this plugin exports, for its own functions and
               * tools to pass around.
               *
               * A plugin's functions belong to every workspace at once, which
               * is why they may not name a workspace's own objects - there is
               * no single workspace whose definitions they could mean. An
               * object declared here belongs to the plugin instead: it
               * travels with it and is available wherever the plugin is,
               * under the plugin's key. Name them here as you spelled them;
               * the loader rewrites the references when it stores them.
               */
              objects() {
                return [];
              }

              types() {
                return [];
              }
            };

            /*
             * What the server does on a plugin's behalf, holding exactly what
             * this plugin was granted.
             *
             * Absent when it was granted nothing, which is why the helpers below
             * say so rather than throwing whatever a call on undefined throws.
             * Every one of them hands its arguments over as JSON and reads JSON
             * back: nothing that crosses is an object either side could walk
             * from.
             */
            /** The standard alphabet, in the order the encoding numbers it. */
            const ORKNUX_BASE64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

            /*
             * Base64, as the whole world's bundles expect to find it.
             *
             * Not a permission and not a door: these two are arithmetic over a
             * string. They reach nothing, so there is nothing to grant, and
             * they are defined here rather than in `orknux` because a bundle
             * written for a browser looks for them by these names and finds
             * nothing else.
             *
             * They are here because of what a bundle does when they are
             * missing. Mermaid decodes a table at module scope with
             *
             *   typeof atob == 'function' ? atob(m) : typeof Buffer.from == ...
             *
             * which reads like a guarded fallback and is not one: `Buffer.from`
             * evaluates `Buffer` before `typeof` ever applies, so the absence
             * of atob did not fall through to Node's way, it threw
             * `ReferenceError: Buffer is not defined` and the plugin could not
             * be loaded at all.
             *
             * Written out rather than switched on because GraalJS has no option
             * for them: of its 108 `js.*` options none mentions base64, which
             * was checked rather than assumed.
             *
             * Binary strings, exactly as the browser's are - one character per
             * byte, not UTF-8. A bundle that wants text does its own decoding
             * on top, and one that wants bytes gets bytes.
             */
            globalThis.atob = function atob(encoded) {
              const text = String(encoded).replace(/[\t\n\f\r ]/g, '');
              if (text.length % 4 === 1) throw new Error('atob: the input is not valid base64');

              let bits = 0;
              let held = 0;
              let out = '';
              for (const character of text) {
                if (character === '=') break;
                const value = ORKNUX_BASE64.indexOf(character);
                if (value < 0) throw new Error('atob: the input is not valid base64');
                held = (held << 6) | value;
                bits += 6;
                if (bits >= 8) {
                  bits -= 8;
                  out += String.fromCharCode((held >> bits) & 0xff);
                }
              }
              return out;
            };

            globalThis.btoa = function btoa(binary) {
              const text = String(binary);
              let out = '';
              for (let at = 0; at < text.length; at += 3) {
                const one = text.charCodeAt(at);
                const two = text.charCodeAt(at + 1);
                const three = text.charCodeAt(at + 2);
                if (one > 0xff || two > 0xff || three > 0xff) {
                  throw new Error('btoa: the input has a character past one byte');
                }

                out += ORKNUX_BASE64[one >> 2];
                out += ORKNUX_BASE64[((one & 3) << 4) | (Number.isNaN(two) ? 0 : two >> 4)];
                out += Number.isNaN(two)
                  ? '='
                  : ORKNUX_BASE64[((two & 15) << 2) | (Number.isNaN(three) ? 0 : three >> 6)];
                out += Number.isNaN(three) ? '=' : ORKNUX_BASE64[three & 63];
              }
              return out;
            };

            globalThis.orknux = {
%SLACK%
%HTTP%
%LOG%
%STORE%
%CRYPTO%
%ENCODING%
%RENDER%
            };

            globalThis.OrknuxParameter = class OrknuxParameter {
              constructor(declared) {
                if (declared === null || typeof declared !== 'object') {
                  throw new Error('an OrknuxParameter needs a declaration');
                }

                this.name = declared.name;
                this.description = declared.description === undefined ? null : declared.description;
                this.type = declared.type;
                this.required = declared.required === undefined ? true : declared.required;
                this.secret = declared.secret === undefined ? false : declared.secret;
                this.connectionType =
                  declared.connectionType === undefined ? null : declared.connectionType;
                /*
                 * The values this may take, where there is a fixed set.
                 *
                 * A plugin choosing between two backends was checking the
                 * string by hand and throwing a sentence listing the choices -
                 * written twice, in the same shape, in two plugins. Declared
                 * instead, the settings page draws a picker: the validation
                 * and its refusal go, and a value that is not on the list
                 * cannot be typed rather than being found at the first call.
                 */
                this.options = declared.options === undefined ? null : declared.options;

                if (typeof this.name !== 'string' || this.name.length === 0) {
                  throw new Error('an OrknuxParameter needs a name');
                }
                if (typeof this.type !== 'string') {
                  throw new Error(this.name + ' needs a type');
                }
                if (typeof this.required !== 'boolean') {
                  throw new Error(this.name + ' says required is neither true nor false');
                }
                if (typeof this.secret !== 'boolean') {
                  throw new Error(this.name + ' says secret is neither true nor false');
                }
                // Said here as well as on the server, so a plugin author is told
                // which half is wrong at the moment they write it rather than at
                // the moment somebody tries to load it.
                if (this.type === 'connection' && typeof this.connectionType !== 'string') {
                  throw new Error(this.name + ' is a connection, so it needs a connectionType');
                }
                if (this.connectionType !== null && this.type !== 'connection') {
                  throw new Error(this.name + ' names a connectionType but is not a connection');
                }
                if (this.options !== null) {
                  if (!Array.isArray(this.options) || this.options.length === 0) {
                    throw new Error(this.name + ' has options, which have to be a non-empty array');
                  }
                  if (this.options.some(function (one) { return typeof one !== 'string' || one.length === 0; })) {
                    throw new Error(this.name + ' has an option that is not a name');
                  }
                  /*
                   * Neither a connection nor a secret is chosen from a list of
                   * values: a connection names a row the workspace has, and a
                   * secret cannot be one of a set somebody can read.
                   */
                  if (this.type === 'connection' || this.secret) {
                    throw new Error(
                      this.name + ' cannot have options: it is a ' + (this.secret ? 'secret' : 'connection'),
                    );
                  }
                }
              }
            };

            /*
             * Every parameter, with what it says about being left out.
             *
             * `required` defaults to true - a declared parameter was always one
             * you passed - and a `default` is stringified here, where it was
             * written, so what reaches the server is the JSON it will store and
             * later pass. Doing it there instead would mean the server
             * re-encoding a value that had already crossed as text.
             *
             * A default implies the parameter is optional: writing one and
             * having it never apply is the trap this exists to remove.
             */
            globalThis.__orknuxParams = function (name, params) {
              if (params === undefined) return [];
              if (!Array.isArray(params)) {
                throw new Error(name + ' declares params that are not an array');
              }
              return params.map(function (one) {
                if (one === null || typeof one !== 'object') {
                  throw new Error(name + ' has a parameter that is not a declaration');
                }
                var has = one.default !== undefined;
                var required = one.required === undefined ? !has : one.required;
                if (typeof required !== 'boolean') {
                  throw new Error(name + "'s " + one.name + ' says required is neither true nor false');
                }
                if (has && required) {
                  throw new Error(
                    name + "'s " + one.name + ' has a default and is required, so the default can never apply',
                  );
                }
                if (has && typeof one.default === 'function') {
                  throw new Error(name + "'s " + one.name + ' has a default that is a function');
                }
                return {
                  name: one.name,
                  type: one.type,
                  description: one.description === undefined ? null : one.description,
                  required: required,
                  defaultJson: has ? JSON.stringify(one.default) : null,
                };
              });
            };

            globalThis.OrknuxFunction = class OrknuxFunction {
              constructor(declared) {
                if (declared === null || typeof declared !== 'object') {
                  throw new Error('an OrknuxFunction needs a declaration');
                }

                this.name = declared.name;
                this.description = declared.description === undefined ? null : declared.description;
                this.params = globalThis.__orknuxParams(String(declared.name), declared.params);
                this.returnType = declared.returnType;
                this.run = declared.run;
                // The implementation as written, for the editor to show beside
                // the declaration: a person deciding whether to take a function
                // over wants to read what it does now.
                this.runSource = typeof this.run === 'function' ? String(this.run) : null;

                if (typeof this.name !== 'string' || this.name.length === 0) {
                  throw new Error('an OrknuxFunction needs a name');
                }
                if (typeof this.returnType !== 'string') {
                  throw new Error(this.name + ' needs a returnType');
                }
                if (typeof this.run !== 'function') {
                  throw new Error(this.name + ' needs a run function; it is what the function does');
                }
                if (!Array.isArray(this.params)) {
                  throw new Error(this.name + ' declares params that are not an array');
                }
              }
            };

            globalThis.OrknuxTool = class OrknuxTool {
              constructor(declared) {
                if (declared === null || typeof declared !== 'object') {
                  throw new Error('an OrknuxTool needs a declaration');
                }

                this.name = declared.name;
                this.description = declared.description === undefined ? null : declared.description;
                this.params = globalThis.__orknuxParams(String(declared.name), declared.params);
                this.returnType = declared.returnType;
                this.run = declared.run;
                this.proxyOf = null;

                if (typeof this.name !== 'string' || this.name.length === 0) {
                  throw new Error('an OrknuxTool needs a name');
                }
                if (typeof this.returnType !== 'string') {
                  throw new Error(this.name + ' needs a returnType');
                }
                if (typeof this.run !== 'function') {
                  throw new Error(this.name + ' needs a run function; it is what the tool does');
                }
                if (!Array.isArray(this.params)) {
                  throw new Error(this.name + ' declares params that are not an array');
                }
              }
            };

            /*
             * A tool that is one of the plugin's own functions, exposed to
             * agents. The utility that says so rather than a copy: the params,
             * return type and implementation are the function's - including any
             * edit somebody makes to it later - and only the name and the
             * model-facing description may be its own.
             */
            globalThis.OrknuxFunctionTool = class OrknuxFunctionTool {
              constructor(declared) {
                if (declared === null || typeof declared !== 'object') {
                  throw new Error('an OrknuxFunctionTool needs a declaration');
                }

                this.proxyOf = declared.function;
                this.name = declared.name === undefined ? declared.function : declared.name;
                this.description = declared.description === undefined ? null : declared.description;

                if (typeof this.proxyOf !== 'string' || this.proxyOf.length === 0) {
                  throw new Error('an OrknuxFunctionTool needs a function to proxy, named by `function`');
                }
                if (typeof this.name !== 'string' || this.name.length === 0) {
                  throw new Error('an OrknuxFunctionTool needs a name');
                }
              }
            };

            /*
             * An instruction set this plugin brings with it.
             *
             * Neither called nor run: `content` is markdown an agent reads.
             * It opens with a `---` frontmatter block naming and describing
             * the skill, the same shape a skill written in the interface has
             * - and a plugin that leaves the block out has it written from
             * the name and description it gave here, because those are the
             * same two facts and asking for them twice is a trap.
             */
            globalThis.OrknuxSkill = class OrknuxSkill {
              constructor(declared) {
                if (declared === null || typeof declared !== 'object') {
                  throw new Error('an OrknuxSkill needs a declaration');
                }

                this.name = declared.name;
                this.description = declared.description === undefined ? null : declared.description;
                this.content = declared.content;

                if (typeof this.name !== 'string' || this.name.length === 0) {
                  throw new Error('an OrknuxSkill needs a name');
                }
                if (typeof this.content !== 'string' || this.content.trim().length === 0) {
                  throw new Error(this.name + ' needs content: the markdown an agent reads');
                }
                if (this.description !== null && typeof this.description !== 'string') {
                  throw new Error(this.name + " has a description that is not text");
                }
              }
            };

            /*
             * A named shape this plugin exports.
             *
             * Each field is checked as it is written, so a shape with a typo
             * in it fails on the line that declares it. Whether an `of` names
             * an object this plugin actually declares needs the whole set, so
             * that is the loader's question.
             */
            globalThis.OrknuxObject = class OrknuxObject {
              constructor(declared) {
                if (declared === null || typeof declared !== 'object') {
                  throw new Error('an OrknuxObject needs a declaration');
                }

                this.name = declared.name;
                this.description = declared.description === undefined ? null : declared.description;
                this.properties = declared.properties === undefined ? [] : declared.properties;

                if (typeof this.name !== 'string' || this.name.length === 0) {
                  throw new Error('an OrknuxObject needs a name');
                }
                if (!Array.isArray(this.properties)) {
                  throw new Error(this.name + ' needs properties, as an array');
                }

                var kinds = ['string', 'number', 'boolean', 'object', 'array'];
                for (var at = 0; at < this.properties.length; at++) {
                  var held = this.properties[at];
                  if (held === null || typeof held !== 'object') {
                    throw new Error(this.name + ' has a property that is not a declaration');
                  }
                  if (typeof held.name !== 'string' || held.name.length === 0) {
                    throw new Error(this.name + ' has a property with no name');
                  }
                  var kind = typeof held.kind === 'string' ? held.kind.toLowerCase() : '';
                  if (kinds.indexOf(kind) === -1) {
                    throw new Error(
                      this.name + "'s " + held.name + ' is a "' + held.kind + '", which is not one of ' +
                        kinds.join(', '),
                    );
                  }
                  var points = kind === 'object' || kind === 'array';
                  var of = held.of === undefined ? null : held.of;
                  if (points && (typeof of !== 'string' || of.length === 0)) {
                    throw new Error(
                      this.name + "'s " + held.name + ' is ' + (kind === 'object' ? 'an object' : 'an array') +
                        ', so it needs an `of`: ' +
                        (kind === 'object' ? 'the object it points at' : 'what it holds'),
                    );
                  }
                  if (!points && of !== null) {
                    throw new Error(this.name + "'s " + held.name + ' names an `of` but is a ' + kind);
                  }
                }
              }
            };

            /*
             * A value type the plugin defines. Issue #377.
             *
             * A name over a base type, what it needs to be told, and up to two
             * functions the server calls on the plugin's behalf: suggest for
             * the picker and validate for the save. The parameters go through
             * OrknuxParameter, so a type is held to the same rules a plugin's
             * own settings are - except that none may be a secret, because
             * what a variable is told rides beside it in the clear.
             */
            globalThis.OrknuxType = class OrknuxType {
              constructor(declared) {
                if (declared === null || typeof declared !== 'object') {
                  throw new Error('an OrknuxType needs a declaration');
                }

                this.name = declared.name;
                this.description = declared.description === undefined ? null : declared.description;
                this.base = declared.base;
                var asked = declared.parameters === undefined ? [] : declared.parameters;

                if (typeof this.name !== 'string' || this.name.length === 0) {
                  throw new Error('an OrknuxType needs a name');
                }
                var bases = ['string', 'number', 'boolean'];
                if (typeof this.base !== 'string' || bases.indexOf(this.base) === -1) {
                  throw new Error(
                    this.name + ' is a "' + this.base + '", which is not one of ' + bases.join(', ') +
                      ' - a type is one of those with a name on it',
                  );
                }
                if (!Array.isArray(asked)) {
                  throw new Error(this.name + ' needs parameters, as an array');
                }
                this.parameters = asked.map(function (one) {
                  var parameter = one instanceof globalThis.OrknuxParameter ? one : new globalThis.OrknuxParameter(one);
                  if (parameter.secret) {
                    throw new Error(
                      this.name + "'s " + parameter.name + ' is a secret, and a type cannot be told one: ' +
                        'what a variable of this type is told is kept beside it, in the clear',
                    );
                  }
                  return parameter;
                }, this);

                if (declared.suggest !== undefined && typeof declared.suggest !== 'function') {
                  throw new Error(this.name + ' has a suggest that is not a function');
                }
                if (declared.validate !== undefined && typeof declared.validate !== 'function') {
                  throw new Error(this.name + ' has a validate that is not a function');
                }
                if (declared.suggest !== undefined) this.suggest = declared.suggest;
                if (declared.validate !== undefined) this.validate = declared.validate;
              }
            };

            globalThis.$CONSTRUCT = function (exported) {
              if (typeof exported !== 'function') {
                throw new Error('the default export must be a class that extends OrknuxPlugin');
              }
              if (!(exported.prototype instanceof globalThis.OrknuxPlugin)) {
                throw new Error('the default export must extend OrknuxPlugin');
              }
              var plugin = new exported();

              /*
               * What this workspace set the plugin's parameters to, put on the
               * instance rather than passed to the constructor: a plugin that writes
               * its own constructor would have to remember to forward them, and one
               * that forgot would be handed nothing with no sign of why.
               *
               * Frozen and not configurable, so a run cannot rewrite what it was
               * given and hand the altered version to whatever it calls next. Empty
               * while the plugin is only being asked what it is.
               */
              Object.defineProperty(plugin, 'settings', {
                value: Object.freeze(JSON.parse(globalThis.$SETTINGS || '{}')),
                writable: false,
                enumerable: true,
                configurable: false,
              });
              return plugin;
            };
        """.trimIndent()
    }
}

/** What a plugin answered when it was loaded and asked. */
sealed interface PluginInspection {

    data class Read(
        val id: String,
        val apiVersion: Int,
        val functions: List<DeclaredFunction>,
        /**
         * What it offers to agents. A proxy has already been resolved against
         * [functions], so its params and return type are the function's own.
         */
        val tools: List<DeclaredTool> = emptyList(),
        val parameters: List<DeclaredParameter> = emptyList(),
        /**
         * What it says it needs, exactly as it wrote it.
         *
         * Names, not [PluginPermission]s, because a name this server does not have
         * is a refusal with a sentence in it rather than something to drop
         * quietly — and dropping it would load a plugin having granted it less
         * than it asked for, which is a plugin that fails later for no stated
         * reason.
         */
        val permissions: List<String> = emptyList(),
        /**
         * What it asks the server to do on its behalf, exactly as it wrote it.
         *
         * Names for the reason the permissions above are names: one this server
         * does not have is a refusal with a sentence in it, not something to drop
         * quietly - and dropping it would load a plugin having granted it less
         * than it asked for, which fails later for no stated reason.
         */
        val capabilities: List<String> = emptyList(),
        /**
         * The library files it says it ships with: relative paths, already
         * shape-checked and normalised (no leading `./`). Whether these match
         * the files that actually arrived is the loader's question - the
         * sandbox only knows what the plugin declared.
         */
        val libraries: List<String> = emptyList(),
        /**
         * The instructions it brings, shape-checked and no more. Whether each
         * body carries the frontmatter a skill needs is decided by the server,
         * which is what owns that format.
         */
        val skills: List<DeclaredSkill> = emptyList(),
        /**
         * The shapes it exports, each field shape-checked and no more.
         * Whether an `of` names one of these, and what reference that becomes,
         * is decided by the server - the sandbox judges one field at a time
         * and never the set.
         */
        val objects: List<DeclaredObject> = emptyList(),
        /** The value types it defines; see [DeclaredType]. Issue #377. */
        val types: List<DeclaredType> = emptyList(),
    ) : PluginInspection

    /** It is not a plugin, or it did not hold up its end of the contract. */
    data class Unreadable(val reason: String) : PluginInspection
}

data class DeclaredFunction(
    val name: String,
    val description: String?,
    val params: List<DeclaredParam>,
    /** As the plugin wrote it. Whether it names a real value type is decided elsewhere. */
    val returnType: String,
    /**
     * The `run` as written, for the editor to show beside the declaration.
     * Reference only - it closes over the plugin and cannot run as a module -
     * and null where the contract could not read it.
     */
    val source: String? = null,
)

/**
 * One argument a function takes.
 *
 * [required] false means a call may leave it out and [default] arrives in its
 * place - which is what deletes the sentinel a plugin otherwise invents. Every
 * plugin written before this said neither, and every one of those parameters
 * is required, which is what its callers already assumed.
 */
data class DeclaredParam(
    val name: String,
    val type: String,
    val required: Boolean = true,
    /** As JSON, because that is what crosses and what will be stored. */
    val default: String? = null,
)

/**
 * One thing a plugin offers to agents.
 *
 * The same shape as a function's declaration plus [proxyOf]: set, it names the
 * plugin's own function this tool stands in front of, and the params and return
 * type here were copied from it at inspection. Null for a tool with a `run` of
 * its own.
 */
data class DeclaredTool(
    val name: String,
    val description: String?,
    val params: List<DeclaredParam>,
    val returnType: String,
    val proxyOf: String?,
)

/**
 * One instruction set a plugin brings with it.
 *
 * Not a third kind of callable: nothing here runs. [content] is markdown an
 * agent reads before doing something, and the plugin ships it so the knowledge
 * of how its work is meant to be done travels with the code that does it.
 */
/**
 * One field of a shape a plugin exports.
 *
 * [of] is the plugin's own spelling of what this points at: the name of
 * another of its objects, or - for an array - a scalar kind. Names rather than
 * references, because a plugin has no ids; turning them into ones is the
 * server's job, and so is refusing a name that points at nothing.
 */
data class DeclaredProperty(
    val name: String,
    /** As the plugin wrote it. Whether it names a kind this server has is decided elsewhere. */
    val kind: String,
    val of: String?,
    val description: String?,
)

/**
 * One named shape a plugin exports.
 *
 * A plugin's functions belong to every workspace at once, so they may not name
 * a workspace's own object definitions - there is no single workspace whose
 * definitions they could mean. One of these belongs to the plugin instead, and
 * travels with it.
 */
data class DeclaredObject(
    val name: String,
    val description: String?,
    val properties: List<DeclaredProperty>,
)

/**
 * A value type a plugin defines. Issue #377.
 *
 * A Slack user id is a string, but it is a string only some values of are real,
 * and the plugin is the one thing that can say which. So a plugin may name a
 * type - `SlackUser` - over a base type, say what it needs to be told to check
 * one (a Slack connection), and offer two functions the server calls on its
 * behalf: `suggest(typed, arguments)` for the picker and `validate(value,
 * arguments)` for the save.
 *
 * Neither function is required. A type with neither is a name on a string,
 * which is still worth something - it says what the variable is for.
 */
data class DeclaredType(
    val name: String,
    val description: String?,
    /** `string`, `number` or `boolean`: what a value of it is underneath. */
    val base: String,
    /** What a variable of this type has to be told, beyond its value - a connection, usually. */
    val parameters: List<DeclaredParameter>,
    val suggests: Boolean,
    val validates: Boolean,
)

data class DeclaredSkill(
    val name: String,
    val description: String?,
    /** Markdown. Whether it opens with the frontmatter a skill needs is the server's question. */
    val content: String,
)

/**
 * One thing a plugin says it has to be told before it can work.
 *
 * Not a function's parameter: a function's is filled in by whoever calls it, node
 * by node, while this is filled in once by the workspace and is the same for every
 * call. A plugin that needs an address to talk to, or a token to talk with, is
 * asking for one of these.
 *
 * [secret] is the plugin saying it is asking for something that should not be
 * typed into a form and stored in the clear. What the server does about that is
 * the server's decision, not the plugin's.
 */
data class DeclaredParameter(
    val name: String,
    val description: String?,
    /** As the plugin wrote it. Whether it names a type this server has is decided elsewhere. */
    val type: String,
    val required: Boolean,
    val secret: Boolean,
    /**
     * The values this may take, where the plugin knows them all.
     *
     * Null where anything typed will do. A set turns the settings field into a
     * picker, which is what deletes the hand-written check a plugin otherwise
     * carries - and a choice that cannot be typed cannot be mistyped.
     */
    val options: List<String>? = null,
    /**
     * Which kind of connection, when [type] is `connection`.
     *
     * A connection parameter is answered by pointing at one of the workspace's
     * connections rather than by typing anything, and the kind is what narrows
     * the list to the ones the plugin can actually use: a Slack plugin handed a
     * Jira connection has been handed a credential it cannot read and will fail
     * at the first call, which is a worse answer than a picker that never
     * offered it. Null for every other type.
     */
    val connectionType: String? = null,
)

@ConfigurationProperties(prefix = "orknux.plugin")
data class PluginProperties(
    /**
     * How long a plugin may take to load. Longer than a function is given: a
     * plugin is a bundle, and evaluating it is more work than calling one small
     * exported function.
     */
    val timeoutMillis: Long = 30_000,

    /** How much of a plugin may run while it is being loaded. */
    val statementLimit: Long = 10_000_000,

    /**
     * The lowest level a plugin's own logging is kept at.
     *
     * Its own setting rather than the functions' one, because a plugin is
     * somebody else's code: an installation debugging a plugin it was handed
     * should not have to turn up every function it wrote itself to do it.
     */
    val logLevel: String = "info",

    /**
     * How full the heap may be, after a collection, before loads and calls start
     * being stopped to save the server.
     */
    val heapPressurePercent: Int = 85,

    /**
     * How much one load or call must have allocated before the heap's trouble is
     * put down to it. Higher than a function's, for the same reason a plugin is
     * given longer: a plugin is a bundle, and evaluating one costs more than
     * calling a small export.
     */
    val suspectAfterBytes: Long = 128L * 1024 * 1024,

    /** How many plugin loads or calls may be in a sandbox at once. */
    val concurrency: Int = 4,

    /** How long one waits for its turn before it is told the server is full. */
    val queueMillis: Long = 10_000,

    /** How much JSON one call may hand back, for the server to carry and store. */
    val resultLimitChars: Long = 4L * 1024 * 1024,
)
