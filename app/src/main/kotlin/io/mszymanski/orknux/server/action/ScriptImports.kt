package io.mszymanski.orknux.server.action

import io.mszymanski.orknux.server.library.LibrarySource
import io.mszymanski.orknux.server.library.ScriptLibraryRepository
import io.mszymanski.orknux.server.variable.VariableArguments
import io.mszymanski.orknux.workflow.script.ScriptModule
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service

/**
 * Works out what a script imports, and in what order it has to be evaluated.
 *
 * The sandbox resolves nothing. It is handed modules that are already found,
 * already flattened and already sorted, and it evaluates them in the order it was
 * given — so this class is the whole of the resolution, and the whole of what could
 * go wrong with it.
 *
 * Two things are asked of it. A save asks it to check an import list before it is
 * stored: does everything exist, may this workspace reach it, and would the graph
 * still be a graph afterwards. A run asks it to assemble what the sandbox needs.
 * Both walk the same edges, which is why they are the same class — a check that
 * walked different edges from the run would be a check that passes what then fails.
 *
 * An import is followed by id and offered under the importer's own name. That is
 * the whole reason a rename costs nothing: the row that points at a function does
 * not hold its name, and the code that calls it does not hold its id.
 */
@Service
class ScriptImports(
    private val functions: WorkflowFunctionRepository,
    private val libraries: ScriptLibraryRepository,
    private val externals: VariableArguments,
) {

    /**
     * Everything [imports] and [libraries] reach, deepest first.
     *
     * The two lists arrive apart and leave together. They are stored apart because
     * they point at two different tables, and they leave together because from
     * inside a script there is no difference worth spelling: both arrive in the one
     * `imports` object, under whatever names the importer chose.
     *
     * Libraries are leaves. A library is one self-contained module — that is what
     * being uploadable means — so nothing is walked underneath it.
     *
     * Answers rather than throws, because most of its callers are runners: a step
     * that cannot assemble its imports has failed, and a failed step is a sentence
     * in a run's history rather than an exception out of a node.
     */
    fun resolve(
        imports: List<ScriptImport>,
        libraryImports: List<ScriptImport> = emptyList(),
    ): ScriptImportsResult {
        if (imports.isEmpty() && libraryImports.isEmpty()) {
            return ScriptImportsResult.Resolved(emptyList(), emptyMap())
        }

        val modules = LinkedHashMap<String, ScriptModule>()
        try {
            libraryImports.forEach { held -> library(held.importedId, modules) }
            imports.forEach { walk(it.importedId, modules, ArrayDeque()) }
        } catch (broken: ImportUnresolvableException) {
            return ScriptImportsResult.Broken(broken.message ?: "could not be assembled")
        }
        return ScriptImportsResult.Resolved(
            modules.values.toList(),
            named(imports) + libraryNames(libraryImports),
        )
    }

    /**
     * Whether [candidate] may be imported by the function with id [importer].
     *
     * The cycle half is asked from the importer's side: a loop exists if the thing
     * being imported already reaches back to the importer, so the walk starts at the
     * candidate and looks for the importer in what it can see. A function that has
     * not been saved yet has no id and cannot be reached by anything, which is why
     * the id is nullable and null is simply not a loop.
     */
    fun requireImportable(candidate: WorkflowFunction, workspaceId: Long, importer: Long?) {
        val id = requireNotNull(candidate.id)
        if (candidate.scope != FunctionScope.WORKSPACE) throw ImportNotEditableException(candidate.name)
        if (candidate.language == CodeLanguage.PYTHON) throw ImportPythonException(candidate.name)
        if (candidate.workspaceId != workspaceId) throw ImportNotFoundException(id)
        if (importer != null) reaching(candidate, importer, ArrayDeque())?.let { throw ImportCycleException(it) }
    }

    /**
     * The path from [from] back to [target], or null when there is none.
     *
     * Returned as the path rather than as a yes, because the sentence somebody has
     * to act on is which functions are in the loop. Depth-bounded like the walk
     * below: a graph the checks let through can never be this deep, so reaching the
     * bound means something else is wrong and stopping is the answer either way.
     */
    private fun reaching(from: WorkflowFunction, target: Long, path: ArrayDeque<String>): List<String>? {
        if (path.size > MAX_DEPTH) return null
        path.addLast(from.name)
        try {
            if (from.id == target) return path.toList()
            for (edge in from.imports) {
                val next = functions.findByIdOrNull(edge.importedId) ?: continue
                reaching(next, target, path)?.let { return it }
            }
            return null
        } finally {
            path.removeLast()
        }
    }

    /**
     * Adds one function and everything under it, in the order they run.
     *
     * Post-order, so a module is written down only once the modules it reads are
     * already written down before it. [modules] is a `LinkedHashMap`, so a diamond —
     * two functions importing the same third — evaluates that third once, which is
     * both cheaper and the only reading under which its module state is one state.
     */
    private fun walk(id: Long, modules: MutableMap<String, ScriptModule>, path: ArrayDeque<Long>) {
        val key = keyOf(id)
        if (modules.containsKey(key)) return
        if (id in path) throw ImportUnresolvableException("imports itself, round a loop")
        if (path.size >= MAX_DEPTH) throw ImportUnresolvableException("imports more than $MAX_DEPTH deep")
        if (modules.size >= MAX_MODULES) throw ImportUnresolvableException("reaches more than $MAX_MODULES scripts")

        val function = functions.findByIdOrNull(id)
            ?: throw ImportUnresolvableException("imports a function that has been deleted")
        if (function.scope != FunctionScope.WORKSPACE) {
            throw ImportUnresolvableException("imports ${function.name}, which is now provided by a plugin")
        }
        // Refused on every save that could cause it; said here too, since what is stored outlives the rule.
        if (function.language == CodeLanguage.PYTHON) {
            throw ImportUnresolvableException("imports ${function.name}, which is written in Python and cannot be imported")
        }

        path.addLast(id)
        try {
            // Its own libraries as well as its own imports. A function reached
            // through another still needs whatever it calls, and a module written
            // down without them would run as far as its first library call.
            function.libraries.forEach { library(it.importedId, modules) }
            function.imports.forEach { walk(it.importedId, modules, path) }
        } finally {
            path.removeLast()
        }

        modules[key] = ScriptModule(
            key = key,
            name = function.name,
            source = function.source,
            imports = named(function.imports) + libraryNames(function.libraries),
            // Its own grants as well as its own imports. A function's externals
            // belong to the function, and an importer is not told they exist —
            // it writes the arguments it was shown and the sandbox appends the
            // rest, exactly as a node's runner does for the function it calls.
            // Without this a function reached through `imports` reads its own
            // variables as `undefined`, which is a wrong answer rather than a
            // failure.
            declared = function.params.size,
            externals = externals.of(function),
        )
    }

    /** Adds one library. A leaf: a library is one module and imports nothing. */
    private fun library(id: Long, modules: MutableMap<String, ScriptModule>) {
        val key = libraryKeyOf(id)
        if (modules.containsKey(key)) return
        if (modules.size >= MAX_MODULES) throw ImportUnresolvableException("reaches more than $MAX_MODULES scripts")

        val held = libraries.findById(id).orElse(null)
            ?: throw ImportUnresolvableException("imports a library that is no longer loaded")
        // The stored text is the file as it arrived, so a CommonJS one is given
        // the `module` and `exports` its code expects here, on the way into the
        // sandbox. Wrapping at this end rather than at the stored end is what
        // keeps the row's hashes claims about what was fetched - see
        // LibrarySource.
        modules[key] = ScriptModule(
            key = key,
            name = held.key,
            source = LibrarySource.runnable(held.source, held.sourceFormat),
        )
    }

    private fun named(imports: List<ScriptImport>): Map<String, String> =
        imports.associate { it.importName to keyOf(it.importedId) }

    private fun libraryNames(imports: List<ScriptImport>): Map<String, String> =
        imports.associate { it.importName to libraryKeyOf(it.importedId) }

    /**
     * Two prefixes, because the two ids are counted separately.
     *
     * Function 3 and library 3 are different things, and a registry keyed by the
     * bare number would have one of them standing in for the other — which is a
     * call into somebody else's code, silently.
     */
    private fun keyOf(id: Long): String = "f$id"

    private fun libraryKeyOf(id: Long): String = "l$id"

    /** Only ever caught in [resolve], which turns it into an answer. */
    private class ImportUnresolvableException(message: String) : RuntimeException(message)

    private companion object {
        /**
         * How far one script may reach through others.
         *
         * Not a design opinion — a stack bound. The walk is recursive, and a chain
         * this long is either a mistake or somebody probing.
         */
        const val MAX_DEPTH = 32

        /** How many scripts one run may have to evaluate before it starts. */
        const val MAX_MODULES = 100
    }
}

/** What a script's imports came to, or why they came to nothing. */
sealed interface ScriptImportsResult {

    /**
     * @param modules everything to evaluate, deepest first.
     * @param imports what the importer itself calls each of them.
     */
    data class Resolved(val modules: List<ScriptModule>, val imports: Map<String, String>) : ScriptImportsResult

    /** Said as a verb phrase, so a caller can put its own subject in front of it. */
    data class Broken(val reason: String) : ScriptImportsResult
}
