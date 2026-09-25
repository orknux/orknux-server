package io.mszymanski.orknux.workflow.script

import java.util.Collections
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.ResourceLimits
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.io.IOAccess
import org.graalvm.polyglot.proxy.ProxyExecutable
import org.graalvm.polyglot.proxy.ProxyObject
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs a workspace's JavaScript.
 *
 * The code comes from whoever can edit a workspace's functions, so it is treated as
 * hostile: the context is given no host classes, no files, no network, no
 * threads, no processes and no environment, and everything crossing the
 * boundary is a JSON string rather than an object. What comes back is text this
 * side parses; nothing the script touches is a live Java object.
 *
 * Two limits stop a script that never finishes: a statement limit, which the
 * language enforces, and a wall-clock timeout, after which the context is
 * cancelled from another thread. A script that spins in a tight loop hits the
 * first; one that blocks in a way statements do not count hits the second.
 *
 * Neither of those is about size, and a script that never finishes is not the
 * only way one can take the server. The context shares this process's heap, so a
 * script that holds what it allocates is spending the room every other thread is
 * about to want — and the thread that runs out first is whichever asks next,
 * which is almost never the script's. So the host bounds three more things, in
 * [ScriptGuard]: how much of the heap a run may be holding when the heap runs
 * short, how many runs may be doing it at once, and how much JSON one may hand
 * back for the rest of the server to carry.
 *
 * The engine is shared, so parsed sources are cached across runs, but every run
 * gets a fresh context: two runs of a function must not be able to see each
 * other's globals.
 *
 * A script may import others. That is done by the host, not by the guest: the
 * modules arrive already resolved and already ordered, are evaluated into a
 * registry, and the importer is given a one-line prelude that reads them out of it
 * under the names it chose. Nothing here follows a path, and nothing here is given
 * a filesystem to follow one with.
 */
@Service
class ScriptRunner(
    private val properties: ScriptProperties,
    /**
     * The server, for the one thing a script may ask it to do on its behalf.
     *
     * The same door a plugin has and the same [PluginHost] behind it — because
     * it is the same question. A function condition asking "is this the first
     * reply in the thread" needs the thread read, the sandbox has no network and
     * is not getting one, and the only honest answer is the server making the
     * call. What crosses is JSON, both ways.
     *
     * Null in the execution module's own tests and anywhere the door is not
     * wired: the helper is still defined, and refuses in words.
     */
    private val host: PluginHost? = null,
    /**
     * The AI session's scratchpad, for `orknux.session.store`. Null in tests
     * and installations that wire none; the helper then says there is no
     * store here.
     */
    private val scratch: SessionScratch? = null,
    /**
     * The AI session's working files, for `orknux.scratchpad`. Null in tests
     * and installations that wire none; the helper then says there are no
     * scratchpads here. Issue #411.
     */
    private val scratchpads: SessionScratchpads? = null,
) {

    private val engine: Engine = Engine.newBuilder("js")
        // Community GraalJS runs in the interpreter on a stock JDK, which is
        // fine for scripts this size and not worth a warning per run.
        .option("engine.WarnInterpreterOnly", "false")
        .build()

    /** Stops a run that outstays its time, its heap, or its turn. */
    private val guard = ScriptGuard(
        "script",
        Bounds(
            timeoutMillis = properties.timeoutMillis,
            heapPressurePercent = properties.heapPressurePercent,
            suspectAfterBytes = properties.suspectAfterBytes,
            concurrency = properties.concurrency,
            queueMillis = properties.queueMillis,
        ),
    )

    /**
     * Calls one function from [source].
     *
     * @param source the module the workspace wrote; its default export is called.
     * @param arguments JSON for each argument, in the order the function takes
     *   them. `null` is a valid JSON document and means the argument is absent.
     * @param context what the script may know about where it is running, as
     *   JSON. It arrives frozen as `context`, and always carries the time.
     * @param modules what this script imports, and what those import in turn,
     *   already in the order they have to be evaluated. Empty for a script that
     *   imports nothing, which is what every script was until now.
     * @param imports the names this script itself imports things under, and the
     *   module each name means.
     * @return what the function returned, as JSON, or what went wrong.
     */
    fun call(
        source: String,
        functionName: String,
        arguments: List<String>,
        context: String = "{}",
        modules: List<ScriptModule> = emptyList(),
        imports: Map<String, String> = emptyMap(),
        /**
         * Which workspace this run belongs to.
         *
         * The boundary on everything [host] will answer: the only connections a
         * script can reach through it are this workspace's. Taken from the run
         * rather than from the script, so nothing anybody writes changes it.
         */
        on: Long? = null,
        /**
         * Which of this installation's things this call belongs to.
         *
         * Only for the log. A line saying `isFirstSlackMessage: no thread` is
         * the beginning of a question rather than an answer: on a server
         * running many workflows at once, the next thing anybody asks is which
         * run said it, and matching on the clock is what this exists to save.
         */
        origin: ScriptOrigin = ScriptOrigin(),
        /**
         * How long this run may hold its thread, in milliseconds, where the tool
         * or function being run carries a timeout of its own or its workspace
         * set a default. Null means the installation's bound, which is what
         * every run had before timeouts were configurable.
         */
        timeoutMillis: Long? = null,
        /**
         * The AI session this call is made inside, or null for one made inside
         * none. It is what scopes `orknux.session.store`: without it the
         * store's doors are simply not bound, and the helper says so.
         */
        sessionId: Long? = null,
    ): ScriptResult {
        val started = System.nanoTime()
        val stopped = AtomicReference<Overrun?>(null)
        /*
         * One list per call, and the guest writes into it through a binding of
         * its own. Synchronised because the guard cancels from another thread:
         * a script stopped mid-line must not leave this half-written for the
         * thread that is building the failure.
         */
        val said = Collections.synchronizedList(mutableListOf<String>())
        return try {
            guard.bounded(stopped, ::newContext, timeoutMillis) {
                ScriptResult.Returned(
                    evaluate(it, source, functionName, arguments, context, modules, imports, on, said, functionName, origin, sessionId),
                    millisSince(started),
                    said.toList(),
                )
            }
        } catch (failure: PolyglotException) {
            val budget = failure.isCancelled || failure.isResourceExhausted
            val reason = when {
                // What the guard says comes first. A cancelled context only says
                // that somebody stopped it, and the guard is the only one who
                // knows whether that was the clock or the heap.
                stopped.get() != null -> "${guard.overrunReason(stopped.get(), timeoutMillis)} and was stopped"
                failure.isCancelled -> "took longer than ${timeoutMillis ?: properties.timeoutMillis} ms and was stopped"
                failure.isResourceExhausted -> exhausted(failure)
                failure.isGuestException -> failure.message ?: "threw"
                else -> failure.message ?: "could not be run"
            }
            ScriptResult.Failed(reason, millisSince(started), settled = !budget && stopped.get() == null, logs = said.toList())
        } catch (failure: ScriptBusyException) {
            // Nothing to do with this script. Worth asking again once the ones
            // ahead of it have finished, which is what unsettled means.
            ScriptResult.Failed(failure.message ?: "could not be run", millisSince(started), settled = false, logs = said.toList())
        } catch (failure: ScriptContractException) {
            // The script ran and did not hold up its end: it threw, or there was
            // nothing to call. Both are answers about the script, not faults here.
            ScriptResult.Failed(failure.message ?: "did not return", millisSince(started), logs = said.toList())
        } catch (failure: IllegalStateException) {
            // Closing a cancelled context races with the call that was in it. If
            // it was the guard that closed it, say what for.
            val overrun = guard.overrunReason(stopped.get(), timeoutMillis)
            if (overrun != null) {
                ScriptResult.Failed("$overrun and was stopped", millisSince(started), settled = false, logs = said.toList())
            } else {
                ScriptResult.Failed(failure.message ?: "could not be run", millisSince(started), logs = said.toList())
            }
        }
    }

    /**
     * Which budget it was that ran out.
     *
     * `isResourceExhausted` is raised both by the statement limit and by a guest
     * heap that could not grow, and those are not the same thing to whoever
     * reads the sentence: one says the script runs too long, the other that it
     * asked for too much at once. Telling the author of a script that ran out of
     * memory that it "ran more than 5,000,000 statements" sends them to count
     * loops that were never the problem.
     *
     * Read off the message because the flag does not distinguish them; the guest
     * heap failure arrives as "Java heap space".
     */
    private fun exhausted(failure: PolyglotException): String {
        val said = failure.message ?: ""
        val memory = said.contains("heap space", ignoreCase = true) ||
            said.contains("out of memory", ignoreCase = true)
        return if (memory) {
            "asked for more memory than it was given and was stopped"
        } else {
            "ran more than ${properties.statementLimit} statements and was stopped"
        }
    }

    /**
     * Parses [source] without running it, for the editor's Validate.
     *
     * A syntax error is what this catches; a script that only fails when it runs
     * is not something parsing can tell anyone about.
     */
    fun validate(source: String): ScriptValidation = try {
        /*
         * Parsing is bounded too, for the same reason running is. It is the one
         * entry point that had no watchdog, which made it the one entry point
         * where a source that took the parser a long time would hold a request
         * thread for as long as it liked - and this one is reached from the
         * editor, by anybody who may write a function.
         */
        guard.bounded(AtomicReference(), ::newContext) { it.parse(module(source)) }
        ScriptValidation(valid = true)
    } catch (failure: PolyglotException) {
        val location = failure.sourceLocation
        ScriptValidation(
            valid = false,
            message = failure.message ?: "the script could not be parsed",
            line = location?.startLine,
            column = location?.startColumn,
        )
    } catch (failure: ScriptBusyException) {
        ScriptValidation(valid = false, message = failure.message ?: "the script could not be checked")
    } catch (failure: IllegalStateException) {
        ScriptValidation(valid = false, message = failure.message ?: "the script could not be checked")
    }

    /**
     * How many arguments the script's default export accepts.
     *
     * Asked so a function can be refused before it is stored when the code and the
     * declared parameters disagree. Arguments are passed positionally — the declared
     * ones, then the workspace's variables — so the count is the whole of the
     * contract; what the code calls them is its own business.
     *
     * Counted from the function's own text rather than from `length`, because
     * `length` stops at the first parameter with a default: `(a, b = 2)` reports one
     * and accepts two, and refusing that would be refusing correct code.
     */
    fun arity(source: String): ScriptArity = try {
        /*
         * Bounded like the other two, and it is the one that needed it most:
         * counting the parameters means evaluating the module, so a source's
         * top-level code runs here - before anybody has agreed to store it. A
         * function that takes the heap in its module body would take it while
         * being checked.
         */
        guard.bounded(AtomicReference(), ::newContext) { polyglot ->
            val exported = polyglot.eval(module(source)).getMember("default")
                ?: return@bounded ScriptArity.Unreadable("it has no default export to call")
            if (!exported.canExecute()) return@bounded ScriptArity.Unreadable("its default export is not a function")

            polyglot.getBindings("js").putMember(SUBJECT, exported)
            polyglot.eval("js", ARITY)
            val counted = polyglot.getBindings("js").getMember(ARITY_RESULT)
            if (counted == null || !counted.isNumber) {
                return@bounded ScriptArity.Unreadable("its parameters could not be read")
            }
            ScriptArity.Counted(counted.asInt())
        }
    } catch (failure: PolyglotException) {
        ScriptArity.Unreadable(failure.message ?: "the script could not be read")
    } catch (failure: ScriptBusyException) {
        ScriptArity.Unreadable(failure.message ?: "the script could not be read")
    } catch (failure: IllegalStateException) {
        ScriptArity.Unreadable(failure.message ?: "the script could not be read")
    }

    /**
     * Evaluates a library and asks what it exports.
     *
     * A library is somebody else's code — very often a bundle nobody here wrote —
     * so it is never asked to answer a question about itself the way a plugin is.
     * It is evaluated, and what its default export turned out to hold is read off
     * the value. That is all this claims: a member is either something to call or
     * something to read, and nothing in a bundle says what its arguments are.
     *
     * Done when the library is loaded rather than when it is first imported, for
     * the reason a plugin is questioned at upload: "this file is not a module with
     * a default export" is an answer worth having while somebody is looking at the
     * file they chose. It runs in the ordinary sandbox, bounded like everything
     * else — a library is given exactly what a function is given, which is nothing.
     *
     * What counts as a member is [MEMBERS], and it is asked in the guest rather
     * than off the value. `Value.memberKeys` answers with the export's own
     * enumerable properties, and a bundle whose default export is an instance
     * keeps its whole API on a prototype: `random` listed `_cache` and `_rng` and
     * not one of the methods anybody imports it for.
     */
    fun library(source: String): LibraryInspection = try {
        guard.bounded(AtomicReference(), ::newContext) { polyglot ->
            val exported = polyglot.eval(module(source, "library")).getMember("default")
                ?: return@bounded LibraryInspection.Unreadable("it has no default export to import")
            if (exported.isNull) {
                return@bounded LibraryInspection.Unreadable("its default export is null")
            }

            val read = polyglot.eval("js", MEMBERS).execute(exported, MAX_MEMBERS)
            if (read.getMember("over").asBoolean()) {
                return@bounded LibraryInspection.Unreadable("its default export has more than $MAX_MEMBERS members")
            }
            val listed = read.getMember("members")
            LibraryInspection.Read(
                callable = exported.canExecute(),
                members = (0 until listed.arraySize).map { at ->
                    val member = listed.getArrayElement(at)
                    LibraryMember(member.getMember("name").asString(), member.getMember("callable").asBoolean())
                },
            )
        }
    } catch (failure: PolyglotException) {
        LibraryInspection.Unreadable(failure.message ?: "it could not be evaluated")
    } catch (failure: ScriptBusyException) {
        LibraryInspection.Unreadable(failure.message ?: "it could not be evaluated")
    } catch (failure: IllegalStateException) {
        LibraryInspection.Unreadable(failure.message ?: "it could not be evaluated")
    }

    /**
     * No host access, and this time none of it.
     *
     * `HostAccess.NONE` reads like the end of the argument and is not: it denies
     * every host method and field, but leaves on the default mappings of guest
     * values onto mutable host types — a guest array read back as a `List`, an
     * object with members read back as a `Map`. Those are conveniences for host
     * code that asks a guest value what it is, and a guest is free to back one
     * with an implementation that behaves like nothing of the sort.
     *
     * Nothing here asks. Everything crossing this boundary is a JSON string, so
     * `NONE` was almost certainly enough in practice. It is replaced anyway
     * because the rest of this builder writes its denials out, and a policy
     * named for having nothing in it is the worst place to leave something
     * unwritten — nobody rereads a line that already says no.
     */
    internal val hostAccess: HostAccess = HostAccess.newBuilder(HostAccess.NONE)
        // Reads backwards: the argument is the list of mappings to allow, and
        // the empty call is therefore the denial. Left off, the builder allows
        // every mapping there is.
        .allowMutableTargetMappings()
        .build()

    /**
     * The sandbox itself. Every `allow…` here is a decision to say no; the
     * builder's defaults are already restrictive, and they are repeated so that
     * loosening one is a visible edit rather than an upgrade's side effect.
     */
    private fun newContext(): Context = Context.newBuilder("js")
        .engine(engine)
        // The options below that remove host globals are marked experimental by
        // GraalJS; they only ever take capability away, so they are worth the
        // acknowledgement.
        .allowExperimentalOptions(true)
        .allowHostAccess(hostAccess)
        .allowHostClassLookup { false }
        .allowHostClassLoading(false)
        .allowIO(IOAccess.NONE)
        .allowCreateThread(false)
        .allowCreateProcess(false)
        .allowNativeAccess(false)
        .allowPolyglotAccess(org.graalvm.polyglot.PolyglotAccess.NONE)
        .allowEnvironmentAccess(org.graalvm.polyglot.EnvironmentAccess.NONE)
        .allowValueSharing(false)
        .resourceLimits(
            ResourceLimits.newBuilder()
                .statementLimit(properties.statementLimit, null)
                .build(),
        )
        // `Java`, `Packages` and `Polyglot` are gone with host and polyglot
        // access; `load` and `print` are turned off by name, since a script has
        // no business reading a file or writing to the process's output.
        .option("js.load", "false")
        .option("js.print", "false")
        // `java` and `Packages` reach nothing once host class lookup is denied,
        // but a global that exists is a global someone will find a use for.
        .option("js.java-package-globals", "false")
        .option("js.polyglot-builtin", "false")
        .option("js.graal-builtin", "false")
        .option("js.ecmascript-version", "2023")
        // Evaluating a module hands back what it exports, which is how the
        // default export is found without the script having to register itself.
        .option("js.esm-eval-returns-exports", "true")
        .build()

    /**
     * Runs the call inside the guest and brings the answer back as text.
     *
     * The result travels through a global that JavaScript writes and this side
     * reads, rather than as a returned value: an async function answers with a
     * promise, and a promise is only settled once the job queue has drained,
     * which happens when the evaluation returns. With no timers, no I/O and no
     * host callbacks in the sandbox, nothing can still be pending by then.
     */
    private fun evaluate(
        polyglot: Context,
        source: String,
        functionName: String,
        arguments: List<String>,
        context: String,
        modules: List<ScriptModule>,
        imports: Map<String, String>,
        on: Long?,
        said: MutableList<String>,
        called: String,
        origin: ScriptOrigin,
        sessionId: Long?,
    ): String? {
        load(polyglot, modules)
        serve(polyglot, on, said, called, origin, sessionId)

        val module = polyglot.eval(module(prelude(imports) + source))
        val function = module.getMember("default")
            ?: throw ScriptContractException("$functionName has no default export to call")
        if (!function.canExecute()) throw ScriptContractException("The default export of $functionName is not a function")

        polyglot.getBindings("js").putMember(CALLEE, function)
        polyglot.getBindings("js").putMember(ARGUMENTS, "[${arguments.joinToString(",")}]")
        polyglot.getBindings("js").putMember(CONTEXT, context)
        // As text, like everything else that crosses: the harness is one cached
        // source, so the bound travels as a value rather than being spliced into
        // the code and parsed afresh on every call.
        polyglot.getBindings("js").putMember(RESULT_LIMIT, properties.resultLimitChars.toString())
        polyglot.eval("js", HARNESS)

        val bindings = polyglot.getBindings("js")
        val error = bindings.getMember(ERROR)
        if (error != null && !error.isNull) throw ScriptContractException(error.asString())

        val result = bindings.getMember(RESULT)
        return if (result == null || result.isNull) null else result.asString()
    }

    /**
     * Evaluates what this script imports, into a registry the preludes read.
     *
     * There is no module resolution in the sandbox and there is not going to be:
     * resolving `import` would mean handing the context a filesystem, virtual or
     * not, and a filesystem is the one thing this class exists to withhold. So the
     * host does the resolving. It is given the modules already sorted — deepest
     * first — and evaluates each one against the registry the ones before it filled,
     * which is why a module's own imports are there by the time its body runs.
     *
     * A module's default export is whatever it exports: a function for one of the
     * workspace's functions, an object for a library. Nothing here decides which,
     * because the importer is the only side that knows what it asked for.
     *
     * They all share one context, and so one set of globals. That is not a gap: a
     * function, the functions it imports and the libraries they use are all the same
     * workspace's code at the same trust level, and separating them would be
     * pretending otherwise.
     */
    private fun load(polyglot: Context, modules: List<ScriptModule>) {
        if (modules.isEmpty()) return
        polyglot.eval("js", REGISTRY)
        /*
         * Held here rather than left on `globalThis`, and that is the whole
         * reason it is an expression: the modules below are somebody's code and
         * they are evaluated one after another, so a global that hands functions
         * their grants would be a global one module could replace before the next
         * one was wrapped in it.
         */
        val granting = if (modules.any { it.externals.isNotEmpty() }) polyglot.eval("js", GRANTS) else null
        val registry = polyglot.getBindings("js").getMember(MODULES)
        for (imported in modules) {
            val exported = polyglot.eval(module(prelude(imported.imports) + imported.source, imported.key))
                .getMember("default")
                ?: throw ScriptContractException("${imported.name} has nothing to import: it has no default export")
            registry.putMember(imported.key, granted(granting, exported, imported))
        }
    }

    /**
     * The module as its importer will call it: its own arguments, then its grants.
     *
     * A function's externals are appended by whoever calls it, because the code
     * takes them as ordinary parameters after the ones it declares. That is done
     * for the function a node runs, and it has to be done here too — the importer
     * writes `imports.f(word)` and does not know a grant exists, so nothing else
     * in the chain is in a position to supply one.
     *
     * A module with no grants is registered exactly as it was, so nothing that
     * imports anything today goes through a wrapper.
     */
    private fun granted(granting: Value?, exported: Value, imported: ScriptModule): Value {
        if (imported.externals.isEmpty() || granting == null) return exported
        return granting.execute(exported, imported.declared, imported.externals.joinToString(",", "[", "]"))
    }

    /**
     * What a module reads its imports out of, on one line.
     *
     * One line because it is prepended to somebody's source and a line of prelude is
     * a line every error message afterwards is out by. A script that imports nothing
     * gets no prelude at all, so nothing that exists today moves.
     *
     * The names are `const`, so what a module imports cannot be reassigned partway
     * through it, and the object is frozen so nothing it imports can be swapped for
     * something else on the way past. Read once, here — a module that tampered with
     * the registry afterwards would be tampering with a copy nobody looks at again.
     */
    /**
     * The one door out, bound before anything is evaluated.
     *
     * A mirror of `PluginRunner.bind`, and deliberately the same shape: the
     * host is a callable taking JSON and answering JSON, so there is no live
     * object on either side to reflect through, and nothing in [newContext] is
     * relaxed to put it there.
     *
     * The `orknux` helper is defined whether or not there is a host behind it.
     * A script calling it where the door is not wired should be told so in a
     * sentence rather than thrown whatever a call on undefined throws.
     */
    /**
     * The helpers, with this installation's log threshold compiled into them.
     *
     * Built once per runner rather than per call: it is the same text every
     * time, and the level is a setting rather than something a script decides.
     */
    private val services: String by lazy {
        val kept = HostHelpers.threshold(properties.logLevel)
        SERVICES
            .replace(
                "%SLACK%",
                HostHelpers.slack(
                    "this installation cannot read Slack threads from a function",
                    "this installation cannot post to Slack from a function",
                    "this installation cannot add Slack reactions from a function",
                    "this installation cannot read Slack messages from a function",
                    "this installation cannot look up Slack users from a function",
                    "this installation cannot resolve Slack mentions from a function",
                    "this installation cannot search Slack from a function",
                    "this installation cannot offer Slack targets from a function",
                ).prependIndent("  "),
            )
            .replace("%HTTP%", HostHelpers.http("this installation cannot make requests from a function").prependIndent("  "))
            .replace("%LOG%", HostHelpers.log(kept).prependIndent("  "))
            .replace("%STORE%", HostHelpers.sessionStore().prependIndent("  "))
            .replace("%SCRATCHPAD%", HostHelpers.scratchpads().prependIndent("  "))
    }

    private fun serve(
        polyglot: Context,
        on: Long?,
        said: MutableList<String>,
        called: String,
        origin: ScriptOrigin,
        sessionId: Long?,
    ) {
        val server = host
        val bindings = polyglot.getBindings("js")

        /*
         * The logging door, which is not a capability and never needed one:
         * nothing is reached by it. It is bound whether or not there is a host,
         * so a function can say something on an installation that wires none.
         */
        bindings.putMember(
            LOG,
            ProxyExecutable { given ->
                val level = given.getOrNull(0)?.takeIf { it.isString }?.asString() ?: "info"
                val line = given.getOrNull(1)?.takeIf { it.isString }?.asString() ?: return@ProxyExecutable null
                val held = "$level $line"
                if (said.size < MAX_LOG_LINES) said += held
                // The script's own words, under a logger of their own so an
                // installation can turn them up or down without touching the
                // server's. The workspace and the function are how somebody
                // finds the one they are looking for.
                val about = "${origin.said(on)} $called: $line"
                when (level) {
                    "debug" -> scriptLog.debug(about)
                    "warn" -> scriptLog.warn(about)
                    "error" -> scriptLog.error(about)
                    else -> scriptLog.info(about)
                }
                null
            },
        )
        if (server != null) {
            val granted = LinkedHashMap<String, Any>()
            /*
             * Only what a function may have without anybody accepting it. A
             * plugin declares what it wants and an administrator agrees to it;
             * a function is written and runs, so what it gets has to be bounded
             * by something other than somebody's judgement. See `forScripts`.
             */
            PluginCapability.entries.filter { it.forScripts }.forEach { capability ->
                granted[capability.name.lowercase()] = ProxyExecutable { given ->
                    val argument = given.firstOrNull()?.takeIf { it.isString }?.asString()
                        ?: return@ProxyExecutable REFUSED
                    server.ask(capability, argument, on)
                }
            }
            bindings.putMember(HOST, ProxyObject.fromMap(granted))
        }
        /*
         * The store's doors, bound only where this call belongs to an AI
         * session. A tool tried from its editor page has no session to store
         * into, and the helper says so instead of writing into nowhere.
         */
        if (scratch != null && sessionId != null) {
            bindings.putMember(
                STORE_PUT,
                ProxyExecutable { given ->
                    val key = given.getOrNull(0)?.takeIf { it.isString }?.asString()
                        ?: return@ProxyExecutable "a key has to be a string"
                    val value = given.getOrNull(1)?.takeIf { it.isString }?.asString()
                        ?: return@ProxyExecutable "a value has to be given"
                    scratch.put(sessionId, key, value)
                },
            )
            bindings.putMember(
                STORE_GET,
                ProxyExecutable { given ->
                    val key = given.getOrNull(0)?.takeIf { it.isString }?.asString()
                        ?: return@ProxyExecutable null
                    scratch.get(sessionId, key)
                },
            )
        }
        /*
         * The scratchpad door, bound the same way and for the same reason: a
         * working file belongs to a session, so a call outside one is told
         * there is nowhere to keep it. One door, request and answer JSON. #411.
         */
        if (scratchpads != null && sessionId != null) {
            bindings.putMember(
                SCRATCHPAD,
                ProxyExecutable { given ->
                    val request = given.getOrNull(0)?.takeIf { it.isString }?.asString()
                        ?: return@ProxyExecutable """{"error":"a scratchpad request has to be a string"}"""
                    scratchpads.act(sessionId, request)
                },
            )
        }
        polyglot.eval("js", services)
    }

    private fun prelude(imports: Map<String, String>): String {
        if (imports.isEmpty()) return ""
        val entries = imports.entries.joinToString(", ") { (name, key) -> "$name: globalThis.$MODULES[\"$key\"]" }
        return "const imports = Object.freeze({ $entries });\n"
    }

    private fun module(source: String, name: String = "function"): Source =
        Source.newBuilder("js", source, "$name.mjs")
            .mimeType("application/javascript+module")
            .buildLiteral()

    private fun millisSince(started: Long): Long = (System.nanoTime() - started) / 1_000_000

    private companion object {
        val log = LoggerFactory.getLogger(ScriptRunner::class.java)

        const val HOST = "__orknuxHost"

        /** Where `orknux.log` hands a line over. Not a capability; nothing is reached by it. */
        const val LOG = "__orknuxLog"

        /** The execution store's two doors; bound only inside a workflow execution. */
        const val STORE_PUT = "__orknuxStorePut"
        const val STORE_GET = "__orknuxStoreGet"
        const val SCRATCHPAD = "__orknuxScratchpad"

        /**
         * A bound on what one call may keep.
         *
         * The lines are held to be handed back with the answer, so a script in a
         * loop writing a line each time round would otherwise build a list the
         * size of its own output. Everything is still written to the server's
         * log; this bounds only what travels with the result.
         */
        const val MAX_LOG_LINES = 500

        /**
         * The scripts' own logger, separate from this class's.
         *
         * So an installation can turn what functions say up or down without
         * touching what the runner itself says about them.
         */
        val scriptLog: Logger = LoggerFactory.getLogger("io.mszymanski.orknux.script")

        /** What a call gets when it was made with something that is not a string. */
        const val REFUSED = """{"error":"that call takes one JSON argument"}"""

        /**
         * `orknux`, as a function sees it.
         *
         * The same helper a plugin gets, written once here for scripts. It reads
         * a connection out of an object or takes a bare id, so a function handed
         * `trigger.connection` and one handed a number both work — which is the
         * difference between a helper somebody can use and one they have to read
         * this file to call.
         */
        val SERVICES = """
            globalThis.orknux = {
%SLACK%
%HTTP%
%LOG%
%STORE%
%SCRATCHPAD%
            };
        """.trimIndent()

        /**
         * More members than a library's export has any business having, and a
         * bound on the answer this hands back to be stored.
         */
        const val MAX_MEMBERS = 500

        const val SUBJECT = "__orknuxSubject"
        const val ARITY_RESULT = "__orknuxArity"

        /**
         * What a library's default export offers, prototypes included.
         *
         * Three decisions, and each one is a name that would otherwise be offered
         * to somebody writing a call.
         *
         * The chain is walked, because a bundle whose export is an instance keeps
         * its methods on a prototype and none of them are own properties. It stops
         * at `Object.prototype` and `Function.prototype`: `hasOwnProperty`,
         * `toString` and `bind` belong to the language and are on everything, so
         * offering them would be offering the same six names for every library
         * ever loaded. For the same reason a function's own `length`, `name` and
         * `prototype` are left out — they are what being a function is, not what
         * this bundle exports.
         *
         * An underscore is left out too. It is the only convention JavaScript has
         * for "not for you", and this list is an offer: `random`'s `_rng` is the
         * thing somebody would call by mistake, not the thing they came for. It is
         * only the offer that drops them — the module a script imports is the real
         * evaluated one, so code that already reaches an internal keeps working.
         *
         * Every read is guarded. A member is callable if reading it gives a
         * function, and reading it can run somebody's getter — one that throws
         * makes that one member unreadable rather than the library.
         */
        val MEMBERS = """
            (function (subject, limit) {
              var language = Object.create(null);
              ['length', 'name', 'prototype', 'constructor', 'caller', 'arguments'].forEach(function (word) {
                language[word] = true;
              });

              var seen = Object.create(null);
              var names = [];
              var over = false;
              var target = subject;

              while (target !== null && target !== undefined &&
                     target !== Object.prototype && target !== Function.prototype) {
                var own;
                try { own = Object.getOwnPropertyNames(target); } catch (unreadable) { own = []; }

                for (var at = 0; at < own.length; at++) {
                  var name = own[at];
                  if (language[name] === true) continue;
                  if (name.charAt(0) === '_') continue;
                  // An index is a position, not the name of anything to call.
                  if (/^\d+${'$'}/.test(name)) continue;
                  if (seen[name] === true) continue;
                  seen[name] = true;
                  names.push(name);
                  if (names.length > limit) { over = true; break; }
                }

                if (over) break;
                try { target = Object.getPrototypeOf(target); } catch (unreadable) { break; }
              }

              names.sort();
              var members = [];
              for (var each = 0; each < names.length; each++) {
                var callable = false;
                try { callable = typeof subject[names[each]] === 'function'; } catch (threw) { callable = false; }
                members.push({ name: names[each], callable: callable });
              }
              return { over: over, members: members };
            })
        """.trimIndent()

        /** Where an imported module's default export is left for the prelude to find. */
        const val MODULES = "__orknuxModules"

        /**
         * A registry with no prototype, so a module named `toString` or
         * `constructor` is the module and not something Object brought with it.
         */
        val REGISTRY = "globalThis.$MODULES = Object.create(null);"

        /**
         * Wraps an imported module so it is handed its own externals.
         *
         * The arguments are taken positionally and exactly: as many as the module
         * declares, padded when the importer passed fewer, and the grants after
         * them. Trimming what is over is the same decision as padding what is
         * short — a grant has a position in the parameter list, and an extra
         * argument that pushed it along would be a variable read as something
         * else. The save-time signature check makes that position exact.
         *
         * The values cross as one JSON document, like everything else that
         * crosses, and are parsed once here rather than on every call.
         */
        val GRANTS = """
            (function (fn, declared, granted) {
              var extras = JSON.parse(granted);
              return function () {
                var passed = [];
                for (var at = 0; at < declared; at++) passed.push(arguments[at]);
                return fn.apply(undefined, passed.concat(extras));
              };
            })
        """.trimIndent()

        /**
         * Counts the parameters the subject declares.
         *
         * Reads the function's own text, so a default value or a destructured
         * parameter counts once, as the caller sees it. Only commas at the top level
         * of the parameter list separate parameters — the ones inside a default
         * value, an object pattern or a string do not — which is why this scans
         * rather than splits.
         */
        val ARITY = """
            (function () {
              var fn = globalThis.$SUBJECT;
              var text = String(fn);
              var open = text.indexOf('(');
              var arrow = text.indexOf('=>');

              // `x => x`: one parameter, and no brackets to look inside.
              if (open === -1 || (arrow !== -1 && arrow < open)) {
                globalThis.$ARITY_RESULT = 1;
                return;
              }

              var depth = 0;
              var commas = 0;
              var anything = false;
              var quote = null;

              for (var at = open; at < text.length; at++) {
                var ch = text[at];

                if (quote !== null) {
                  if (ch === '\\') at++;
                  else if (ch === quote) quote = null;
                  continue;
                }
                if (ch === '"' || ch === "'" || ch === '`') { quote = ch; continue; }

                if (ch === '(' || ch === '[' || ch === '{') { depth++; if (depth === 1) continue; }
                else if (ch === ')' || ch === ']' || ch === '}') { depth--; if (depth === 0) break; }

                if (depth === 1) {
                  if (ch === ',') commas++;
                  else if (ch !== ' ' && ch !== '\n' && ch !== '\r' && ch !== '\t') anything = true;
                }
              }

              globalThis.$ARITY_RESULT = anything ? commas + 1 : 0;
            })();
        """.trimIndent()

        const val CALLEE = "__orknuxCallee"
        const val CONTEXT = "__orknuxContext"
        const val ARGUMENTS = "__orknuxArguments"
        const val RESULT = "__orknuxResult"
        const val ERROR = "__orknuxError"
        const val RESULT_LIMIT = "__orknuxResultLimit"

        /**
         * Calls the function and settles whatever it answered with, leaving JSON
         * behind. Written as one expression so a script cannot shadow it.
         *
         * The answer is measured before it is handed over, and an oversized one
         * is refused here rather than on the other side of the boundary. What a
         * function returns does not stop at being a string: it is written to the
         * step's row, carried to whatever node reads it next, and parsed into a
         * tree on the way. A hundred megabytes of JSON is a hundred megabytes in
         * each of those places, and the only cheap place to say no is the one
         * where the string has just been made.
         */
        val HARNESS = """
            (function () {
              globalThis.$RESULT = null;
              globalThis.$ERROR = null;
              try {
                // What the script may know about where it runs. Frozen, so one
                // call cannot leave anything behind for the next.
                globalThis.context = Object.freeze(JSON.parse(globalThis.$CONTEXT));
                var args = JSON.parse(globalThis.$ARGUMENTS);
                var limit = Number(globalThis.$RESULT_LIMIT);
                Promise.resolve(globalThis.$CALLEE.apply(null, args)).then(
                  function (value) {
                    var json = value === undefined ? null : JSON.stringify(value);
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
    }
}

/** What a library turned out to export, or why that could not be read. */
sealed interface LibraryInspection {

    /**
     * @param callable whether the export is itself something to call, rather
     *   than an object with members on it. Both spellings are common in a bundle.
     */
    data class Read(val callable: Boolean, val members: List<LibraryMember>) : LibraryInspection

    data class Unreadable(val reason: String) : LibraryInspection
}

/** One thing a library's default export holds, and whether it is a function. */
data class LibraryMember(val name: String, val callable: Boolean)

/**
 * One script that another script imports.
 *
 * The sandbox has no module resolution, so an import is not a path the guest
 * follows — it is a module the host evaluated first and left in a registry. What
 * the importer writes is `imports.someName`, and this is what `someName` came to.
 *
 * [key] is how the registry holds it and is the host's to choose; it never appears
 * in anybody's code, which is why it can be an id and does not have to survive a
 * rename. [name] is only for a sentence when something goes wrong.
 */
data class ScriptModule(
    val key: String,
    val name: String,
    val source: String,
    /** What this module imports in turn: the name it uses, and the key it means. */
    val imports: Map<String, String> = emptyMap(),

    /**
     * How many arguments its own code declares before [externals] begin.
     *
     * Named rather than counted off the function, because it is what decides
     * where a grant lands. An importer that passed one argument to a module
     * declaring two would otherwise have its argument followed straight by a
     * variable, and the module would read a secret as its second parameter.
     */
    val declared: Int = 0,

    /**
     * The workspace's variables this module is granted, as JSON, in the order it
     * receives them — after everything it declares.
     *
     * A grant belongs to the module that declared it, and an importer is never
     * told it exists: it writes `imports.f(word)` and the sandbox appends the
     * rest. Empty for a library, which is somebody else's bundle and is granted
     * nothing.
     */
    val externals: List<String> = emptyList(),
)

/** What a run of a script produced. */
sealed interface ScriptResult {

    val durationMillis: Long

    /**
     * @param json what the function returned; null when it returned nothing.
     * @param logs what it said on the way, in order. Kept on the answer as well
     *   as written to the server's log, so whatever called this can show them
     *   next to the result they belong to.
     */
    data class Returned(
        val json: String?,
        override val durationMillis: Long,
        val logs: List<String> = emptyList(),
    ) : ScriptResult

    /** The script threw, ran too long, or never got as far as running. */
    /**
     * @param settled whether asking again could ever answer differently.
     *
     * A script cannot reach anything: no IO, no network, no clock it can wait
     * on. So a script that threw will throw again, given the same arguments,
     * and running it twice more only reaches the same conclusion twice more.
     * The budgets are the exception - a run that was stopped by the clock, or
     * that could not be given the memory it asked for, was stopped by how busy
     * the machine was rather than by anything about the script, and a quieter
     * machine may well answer.
     */
    data class Failed(
        val reason: String,
        override val durationMillis: Long,
        val settled: Boolean = true,
        /**
         * What it said before it stopped.
         *
         * This is the case the logging is for: a script that returned an answer
         * can be read from its answer, and one that threw halfway leaves nothing
         * but a sentence about where.
         */
        val logs: List<String> = emptyList(),
    ) : ScriptResult
}

/** How many arguments a script's default export takes, or why that is not knowable. */
sealed interface ScriptArity {

    data class Counted(val parameters: Int) : ScriptArity

    /** No default export, not a function, or it could not be read. */
    data class Unreadable(val reason: String) : ScriptArity
}

data class ScriptValidation(
    val valid: Boolean,
    val message: String? = null,
    val line: Int? = null,
    val column: Int? = null,
)

/** The script ran but did not hold up its end: no default export, or it threw. */
class ScriptContractException(message: String) : RuntimeException(message)

@ConfigurationProperties(prefix = "orknux.script")
data class ScriptProperties(
    /**
     * How long one call may take. The context is cancelled from another thread
     * when it runs out, which stops a script that has stopped making progress.
     */
    val timeoutMillis: Long = 30_000,

    /**
     * How many statements one call may run. This is what catches a tight loop
     * before the clock does, and it is counted by the language rather than
     * observed from outside.
     */
    val statementLimit: Long = 5_000_000,

    /**
     * How full the heap may be, after a collection, before calls start being
     * stopped to save the server.
     *
     * Post-collection occupancy is live data, so eighty-five per cent of it is a
     * server that is nearly out of room rather than one that is merely busy. A
     * healthy installation never sees this; one that does is a few seconds from
     * an OutOfMemoryError on whichever thread asks next, and the thread that asks
     * next is usually not the script's.
     */
    val heapPressurePercent: Int = 85,

    /**
     * The lowest level a script's own logging is kept at.
     *
     * Decided in the sandbox, before a line crosses: a `debug` call on an
     * installation logging at `info` costs one comparison and is dropped where
     * it was written. So a function may leave its tracing in and pay for it only
     * when somebody turns this down to find something.
     *
     * `debug`, `info`, `warn`, `error`, or `off` for none of it.
     */
    val logLevel: String = "info",

    /**
     * How much a call must have allocated before the heap's trouble is put down
     * to it.
     *
     * Not a limit. A call may allocate as much as it likes while there is room -
     * and it will, since the interpreter boxes arithmetic and an honest loop
     * churns through hundreds of megabytes a second keeping none of it. This is
     * only the line between a bystander and a suspect, so that a small function
     * running beside somebody else's leak is not the one that gets stopped.
     */
    val suspectAfterBytes: Long = 64L * 1024 * 1024,

    /**
     * How many calls may be in a sandbox at once.
     *
     * What bounds the installation rather than the call. Scripts are small and
     * quick - tens of milliseconds each - so four at a time is a great many calls
     * a second, and it means four contexts' worth of live data at worst rather
     * than one per request the server happens to be serving.
     */
    val concurrency: Int = 4,

    /**
     * How long a call waits for its turn before it is told the server is full.
     *
     * Waiting is the right answer to a burst and the wrong answer to a queue that
     * is never going to clear, so there is a limit on it. What comes back is
     * unsettled, so a workflow step retries rather than failing outright.
     */
    val queueMillis: Long = 5_000,

    /**
     * How much JSON one call may hand back.
     *
     * Not a bound on the heap - the two above are that - but a bound on what the
     * rest of the server is asked to carry, which is a separate thing and costs
     * three times over: the answer is written to the step's row, parsed into a
     * tree on the way there, and handed to whatever node reads it next.
     */
    val resultLimitChars: Long = 4L * 1024 * 1024,
)
