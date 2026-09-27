package io.mszymanski.orknux.server.action

import io.mszymanski.orknux.server.graphql.Refusal
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Embeddable
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OrderColumn
import jakarta.persistence.Table
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime

/** The shape of a value crossing the boundary between a workflow and a script. */
enum class ValueType {
    STRING,
    NUMBER,
    BOOLEAN,

    /**
     * One of the workspace's objects, named by id alongside this.
     *
     * A shape somebody defined, so the editor can annotate a parameter with it and
     * the language service can check what the code does to it. Without the id this
     * says nothing, which is why anything carrying it must carry that too.
     */
    OBJECT,

    /**
     * Keys and values, with no promise about either.
     *
     * What OBJECT used to mean on its own. Kept as its own type rather than as
     * OBJECT-with-nothing-attached, because "I have not said what this is" and "this
     * is genuinely free-form" are different statements, and only one of them is
     * worth prompting somebody to fix.
     */
    MAP,

    ARRAY,

    /**
     * One of the workspace's connections, as the thing that calls this hands it
     * over.
     *
     * **What arrives is the connection's id, and this type is about saying so.**
     * A payload carries text, so a trigger publishes the connection it arrived
     * on as `"7"`; a function declaring `string` would take that and work, and
     * nobody reading the function would know what the string was for. This is
     * the same value with a name on it: the panel offers the workspace's
     * connections rather than a free box, and the editor annotates the parameter
     * as something to hand to a call that takes one.
     *
     * It is deliberately not a type per kind of connection. Which Slack a
     * workspace means is a question about a row, and a function handed the wrong
     * kind is told so by the call it makes - `SlackThreads` refuses a Jira
     * connection in a sentence - rather than by an annotation this would have to
     * keep in step with every connection type ever added.
     */
    CONNECTION,

    /**
     * Nothing at all.
     *
     * A function that posts a message or writes a row has no answer to give,
     * and making it declare an object it does not have leaves a node with an
     * output port nothing will ever read.
     */
    NONE,
}

/**
 * How a value's shape is written in TypeScript.
 *
 * The editor's annotations, and the stubs a new function starts from, are written
 * against this — so a parameter declared as an object is annotated the way the
 * language service will actually check it.
 *
 * An object is `Record<string, unknown>` and an array `unknown[]`, not `object` and
 * `any[]`: everything crossing into the sandbox arrived as JSON, so what is inside
 * is genuinely unknown until the code looks. `unknown` makes the code look;
 * `any` would let a typo through with no complaint, which is the whole reason for
 * having types here at all.
 */
fun typeScriptType(type: ValueType, objectName: String? = null): String = when (type) {
    ValueType.STRING -> "string"
    ValueType.NUMBER -> "number"
    ValueType.BOOLEAN -> "boolean"
    /*
     * A connection, however it reached the function: the id as text off a
     * payload, as a number where somebody typed one, or the whole object from a
     * plugin's settings. The editor declares that union; naming it here rather
     * than spelling it out keeps the two ends saying one thing.
     */
    ValueType.CONNECTION -> "OrknuxConnectionRef"
    // The object's own name, which the editor declares as an interface. Falls back
    // to the loose shape if the object it named has since been deleted — an
    // annotation that does not resolve would light up code that still runs.
    ValueType.OBJECT -> objectName ?: "Record<string, unknown>"
    ValueType.MAP -> "Record<string, unknown>"
    ValueType.ARRAY -> "unknown[]"
    // Only ever a return type, and a function returning nothing returns void.
    ValueType.NONE -> "void"
}

/** One argument a function takes, in the order it takes them. */
@Embeddable
class FunctionParam(
    @Column(name = "name", nullable = false, length = 64)
    var name: String = "",

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    var type: ValueType = ValueType.STRING,

    /**
     * Which object, when the type is one. Null for everything else.
     *
     * Spelled the same way an object's own property spells it — a column holding an
     * id, no foreign key — so the two places that point at an object point at it
     * alike, and a deleted object leaves a dangling id that is reported rather than
     * a delete that is refused.
     */
    @Column(name = "object_id")
    var objectId: Long? = null,

    /**
     * Whether a caller has to supply it.
     *
     * True for everything written before this existed, which is what every
     * caller already assumed: a declared parameter was a parameter you passed.
     * False means a call may leave it out, and what arrives instead is
     * [defaultJson].
     */
    @Column(nullable = false)
    var required: Boolean = true,

    /**
     * What arrives when an optional one is left out, as JSON.
     *
     * JSON rather than a typed column because a parameter is one of five
     * types and this is one column - and because what crosses into a sandbox
     * is JSON anyway, so a default stored as the text that will be passed is
     * a default nothing has to convert at the moment it is used.
     *
     * Null on an optional parameter means null is the default, which is what
     * a function got before it could say otherwise.
     */
    @Column(name = "default_json", columnDefinition = "text")
    var defaultJson: String? = null,
)

/**
 * One thing a script imports, and the name it imports it under.
 *
 * Two halves, and they are not the same fact. [importedId] is the reference, and it
 * is an id because a name in a database is a name that goes stale: this product has
 * already been through a grant held by name and stranded the first time somebody
 * renamed what it pointed at, and an import stranded that way would be a workflow
 * that fails at the moment it runs.
 *
 * [importName] is the importer's own word for it, written into the importer's own
 * code. It is deliberately not the imported thing's name. If it were, renaming a
 * function would silently break every function that calls it — the reference would
 * survive and the code would not. So the name belongs to whoever wrote the call,
 * the id belongs to whoever is being called, and neither can spoil the other.
 *
 * Which table the id is in is decided by which collection this is in, not by a
 * kind stored beside it: a function's imports and its libraries are two lists
 * pointing at two tables, and one column that could mean either is one column that
 * will one day mean the wrong one.
 */
@Embeddable
class ScriptImport(
    @Column(name = "imported_id", nullable = false)
    var importedId: Long = 0,

    @Column(name = "import_name", nullable = false, length = 64)
    var importName: String = "",
)

/**
 * A variable this function is handed, whatever calls it.
 *
 * A declared parameter is the caller's to fill; an external one is not. The
 * workspace decides what it holds, and the function receives it as an argument
 * after the ones it declares — which is how a script gets at a secret without
 * anybody pasting the secret into a graph.
 */
@Embeddable
class FunctionExternal(
    @Column(name = "variable_id", nullable = false)
    var variableId: Long = 0,
)

/** Where a function came from, and therefore who may change it. */
enum class FunctionScope {

    /** Written in a workspace, editable there. */
    WORKSPACE,

    /**
     * Declared by a plugin. Available in every workspace and externally managed:
     * usable from actions, triggers and conditions, but not editable — the plugin
     * that declared it is the only thing that can change it, by being loaded again.
     *
     * Named for where it came from rather than how far it reaches. "Organisation"
     * would describe the visibility and hide the useful part: a picker offering
     * `isTeammate` should say which plugin brought it, because that is what tells
     * somebody what it does and who to ask.
     */
    PLUGIN,

    /**
     * Brought by the release itself, out of a bundle Orknux embeds. Issue #501.
     *
     * The same shape as a plugin's - declared elsewhere, usable everywhere, not
     * editable here - and a different origin: there is no plugin row behind it
     * and nothing to install or remove, because the capability is the product's
     * own. Making a document is not an integration with somebody else's system.
     *
     * Kept apart from PLUGIN rather than folded into it so that nothing has to
     * ask whether the plugin a function names still exists: for these, none
     * ever did.
     */
    EMBEDDED,
}

/**
 * A named piece of JavaScript a workspace wrote, callable from an action.
 *
 * The source is a module whose default export is the function; it runs in the
 * sandbox `ScriptRunner` builds, with no host, no files and no network, so what
 * is stored here is only ever text.
 *
 * Or it is a function a plugin declared, in which case it belongs to the
 * organisation rather than to a workspace and nothing here is editable. The two
 * are exclusive, and the database says so as well as this comment does.
 */
@Entity
@Table(name = "workflow_function")
class WorkflowFunction(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    /** Null for an organisation function: it belongs to no single workspace. */
    @Column(name = "workspace_id")
    val workspaceId: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var scope: FunctionScope = FunctionScope.WORKSPACE,

    /** The plugin that declared it, and whose unloading takes it away. */
    @Column(name = "plugin_id")
    var pluginId: Long? = null,

    @Column(nullable = false, length = 120)
    var name: String,

    @Column(length = 4000)
    var description: String? = null,

    /**
     * How long one call of this function may run, in seconds.
     *
     * Null means the function has decided nothing: the workspace's default is
     * used, and the installation's bound where the workspace has none. Read per
     * call, so changing it changes the next call rather than one in flight.
     */
    @Column(name = "timeout_seconds")
    var timeoutSeconds: Int? = null,

    /**
     * When somebody edited a plugin's function, and who.
     *
     * Null means the plugin's declaration still speaks for this row, which is
     * what every plugin function has until somebody edits one - and what every
     * workspace function has always, since editing those is simply saving.
     * Set, three things change: the code runs from this row rather than out of
     * the plugin's bundle, a plugin reload leaves the row alone, and the
     * plugin's export carries this version rather than the declared one.
     */
    @Column(name = "edited_at")
    var editedAt: java.time.OffsetDateTime? = null,

    @Column(name = "edited_by", length = 120)
    var editedBy: String? = null,

    /**
     * The JavaScript that runs.
     *
     * Compiled from [typescript] for anything a workspace wrote — the sandbox runs
     * JavaScript and nothing compiles at run time, so what runs is stored compiled.
     */
    @Column(nullable = false, columnDefinition = "text")
    var source: String,

    /**
     * The TypeScript it was written in, or null when it was not written here.
     *
     * The editor's left column. Kept beside the compiled JavaScript rather than
     * instead of it, because neither can be recovered from the other: types are
     * gone by the time it is JavaScript, and nothing in the sandbox could compile
     * it back. They are written in the same save, and the editor compiles the one
     * it is saving rather than reusing an earlier compile — so what is stored here
     * is always the source of what is stored above.
     *
     * Null for a plugin's function: it was written in the plugin, and this is not
     * where it changes.
     */
    @Column(columnDefinition = "text")
    var typescript: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "return_type", nullable = false, length = 16)
    var returnType: ValueType = ValueType.MAP,

    /** Which object it returns, when it returns one. Null for everything else. */
    @Column(name = "return_object_id")
    var returnObjectId: Long? = null,

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "workflow_function_param", joinColumns = [JoinColumn(name = "function_id")])
    @OrderColumn(name = "position")
    var params: MutableList<FunctionParam> = mutableListOf(),

    /**
     * The workspace's variables this function is handed, in the order it
     * receives them — after everything it declares.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "workflow_function_external", joinColumns = [JoinColumn(name = "function_id")])
    @OrderColumn(name = "position")
    var externals: MutableList<FunctionExternal> = mutableListOf(),

    /**
     * The workspace's other functions this one calls, under the names it calls them.
     *
     * Not arguments: an import is reached through `imports`, not through the
     * signature, which is why adding one does not change what the code has to
     * accept. Empty for a plugin's function — a plugin brings whatever it needs
     * inside itself, and an organisation-level function has no workspace whose
     * functions it could reach.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "workflow_function_import", joinColumns = [JoinColumn(name = "function_id")])
    @OrderColumn(name = "position")
    var imports: MutableList<ScriptImport> = mutableListOf(),

    /**
     * The installation's libraries this function imports, under the names it uses.
     *
     * A separate list from the functions it imports, pointing at a separate table.
     * They arrive in the same `imports` object and are written the same way in the
     * code, because from inside a script there is no difference worth spelling —
     * but a single column that could hold either kind of id is a column that will
     * one day hold the wrong one, and for an import that is a call into whatever
     * happened to have that number.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "workflow_function_library", joinColumns = [JoinColumn(name = "function_id")])
    @OrderColumn(name = "position")
    var libraries: MutableList<ScriptImport> = mutableListOf(),

    @Column(name = "last_modified_at", nullable = false)
    var lastModifiedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "last_modified_by", nullable = false, length = 120)
    var lastModifiedBy: String = "",
) {

    /** "(input: map, format: string)", as the entity can say it without names. */
    val signature: String
        get() = params.joinToString(", ", "(", ")") { "${it.name}: ${it.type.name.lowercase()}" }

    /**
     * Whether a workspace may change this.
     *
     * False for anything a plugin declared. Asked by the API before every edit and
     * reported to the screen, so the button that is hidden and the mutation that
     * refuses are answering the same question.
     */
    val editable: Boolean
        get() = scope == FunctionScope.WORKSPACE
}

interface WorkflowFunctionRepository : JpaRepository<WorkflowFunction, Long> {

    fun findByWorkspaceId(workspaceId: Long, pageable: Pageable): Page<WorkflowFunction>

    fun findByWorkspaceId(workspaceId: Long): List<WorkflowFunction>

    fun findByWorkspaceIdAndName(workspaceId: Long, name: String): WorkflowFunction?

    /**
     * One workspace's functions and the organisation's together.
     *
     * A single query, so the page and the ordering are the database's answer
     * rather than something reassembled afterwards from two of them.
     */
    @Query("select f from WorkflowFunction f where f.workspaceId = :workspaceId or f.scope = 'PLUGIN'")
    fun findByWorkspaceIdOrPlugin(workspaceId: Long, pageable: Pageable): Page<WorkflowFunction>

    /**
     * The same list, narrowed to what a word appears in.
     *
     * The name and the description. The population clause is the one above,
     * unchanged, so a search can never surface a row the unsearched list would
     * not have shown - and it is asked of the database rather than sieved in
     * the browser because the list is paged.
     */
    @Query(
        "select f from WorkflowFunction f " +
            "where (f.workspaceId = :workspaceId or f.scope = \'PLUGIN\') " +
            "and (lower(f.name) like lower(concat(\'%\', :looking, \'%\')) " +
            "or lower(coalesce(f.description, \'\')) like lower(concat(\'%\', :looking, \'%\')))",
    )
    fun searching(
        @Param("workspaceId") workspaceId: Long,
        @Param("looking") looking: String,
        pageable: Pageable,
    ): Page<WorkflowFunction>

    /**
     * The same list, narrowed to one origin: the workspace's own rows, or the
     * ones the plugins brought. The workspace clause stays in for the plugin
     * half too - it is simply never what matches - so the two queries page the
     * same population and a filter cannot show a row the full list would not.
     */
    @Query(
        "select f from WorkflowFunction f " +
            "where (f.workspaceId = :workspaceId or f.scope = 'PLUGIN') and f.scope = :scope",
    )
    fun findByWorkspaceIdOrPluginScoped(
        workspaceId: Long,
        scope: FunctionScope,
        pageable: Pageable,
    ): Page<WorkflowFunction>

    /** Narrower still: what one plugin brought, which is a question with a name in it. */
    @Query("select f from WorkflowFunction f where f.scope = 'PLUGIN' and f.pluginId = :pluginId")
    fun findByPluginIdPaged(pluginId: Long, pageable: Pageable): Page<WorkflowFunction>

    fun findByScopeAndName(scope: FunctionScope, name: String): WorkflowFunction?

    /** Everything one plugin declared, which is what a reload reconciles against. */
    fun findByPluginId(pluginId: Long): List<WorkflowFunction>

    /**
     * Every function that imports the one with this id.
     *
     * Asked before a delete, and asked while looking for a cycle. Written as a
     * query rather than by loading a workspace and filtering it in memory, because
     * a cycle check walks this repeatedly and the answer is a join.
     */
    @Query("select f from WorkflowFunction f join f.imports i where i.importedId = :functionId")
    fun findByImportedFunctionId(functionId: Long): List<WorkflowFunction>

    /** Every function that imports the library with this id. */
    @Query("select f from WorkflowFunction f join f.libraries l where l.importedId = :libraryId")
    fun findByImportedLibraryId(libraryId: Long): List<WorkflowFunction>
}

class FunctionNotFoundException(val id: Long) : RuntimeException("No function with id $id"), Refusal {

    override val arguments get() = mapOf("id" to id)
}

class FunctionNameTakenException(val name: String) :
    RuntimeException("A function named \"$name\" already exists in this workspace"), Refusal {

    override val arguments get() = mapOf("name" to name)
}

/**
 * Somebody tried to change a function a plugin declared.
 *
 * These are the plugin's to define. Loading the plugin again is how they change;
 * nothing in a workspace may edit or delete one.
 */
class FunctionExternallyManagedException(val name: String) : RuntimeException(
    "\"$name\" is provided by a plugin and cannot be changed here. " +
        "Load the plugin again to change what it declares.",
), Refusal {

    override val arguments get() = mapOf("name" to name)
}

class FunctionNameInvalidException(val name: String) :
    RuntimeException("\"$name\" is not a name a script can be called by"), Refusal {

    override val arguments get() = mapOf("name" to name)
}

class FunctionParamInvalidException(val name: String) :
    RuntimeException("\"$name\" is not a name a parameter can have"), Refusal {

    override val arguments get() = mapOf("name" to name)
}

/**
 * A parameter says it takes an object without saying which.
 *
 * OBJECT is a reference to something the workspace defined; on its own it is not a
 * type at all. MAP is what to use for a shape nobody has written down — and the
 * message says so, because that is the choice being made.
 */
class FunctionObjectRequiredException(val name: String) : RuntimeException(
    "\"$name\" is declared as an object but no object is chosen. Pick one of this " +
        "workspace's objects, or use map for a structure without a defined shape.",
), Refusal {

    override val arguments get() = mapOf("name" to name)
}

class FunctionSourceInvalidException(val reason: String) : RuntimeException(reason), Refusal {

    override val arguments get() = mapOf("reason" to reason)
}

/**
 * A description longer than the column it lives in.
 *
 * Said in characters, because the person pasted text and can count it — before
 * this the database refused it and the editor showed an internal error, which
 * told them nothing about what to shorten.
 */
class FunctionDescriptionTooLongException(val length: Int, val limit: Int) : RuntimeException(
    "The description is $length characters and at most $limit fit",
), Refusal {

    override val arguments get() = mapOf("length" to length, "limit" to limit)
}

/**
 * A plugin's function keeps the name the plugin declared.
 *
 * The name is how the registry matches rows on reload: renamed, the row would
 * read as one function gone and another arrived, and the edit this feature
 * exists to keep would be the thing that lost it.
 */
class FunctionPluginNameHeldException(val name: String) : RuntimeException(
    "$name is a plugin's function and keeps the name the plugin declared; everything else about it may change",
), Refusal {

    override val arguments get() = mapOf("name" to name)
}

/**
 * A plugin's function belongs to no workspace, so nothing on it may name one
 * workspace's things — an object, a variable, an import, a library. Which thing
 * was asked for is in the sentence, because that is what has to come off the
 * save.
 */
class FunctionPluginNeedsNoWorkspaceException(val wanted: String) : RuntimeException(
    "A plugin's function belongs to every workspace at once, so it cannot $wanted - those are one workspace's own",
), Refusal {

    override val arguments get() = mapOf("wanted" to wanted)
}

/**
 * A test run was handed something that is not JSON.
 *
 * Everything crossing into the sandbox is JSON text, and the harness parses the
 * whole argument list at once — so an unparseable value fails as a syntax error
 * about a list the caller never wrote, at a position that means nothing to them.
 * Refused here instead, while the value still has a parameter's name on it.
 */
class FunctionArgumentInvalidException(name: String, reason: String?) : RuntimeException(
    "\"$name\" was not given a value this can pass to the function" +
        (if (reason.isNullOrBlank()) "" else ": $reason"),
)

/**
 * One half of a function's code arrived without the other.
 *
 * A function is written in TypeScript and runs as the JavaScript compiled from it,
 * and the two are stored together for one reason: either on its own is a lie.
 * Storing JavaScript alone would show the next person the compiler's output as
 * though they had written it; storing TypeScript alone would leave the sandbox
 * running the version before the edit.
 *
 * So they are saved in the same write or not at all. Refused here rather than
 * guessed at, because there is no way to derive the missing one — the compiler
 * lives in the editor, and this side cannot type-strip or un-type-strip anything.
 */
class FunctionCodeIncompleteException(val missing: String) : RuntimeException(
    "The $missing is missing. A function's TypeScript and the JavaScript compiled " +
        "from it are saved together, so that what runs is always what was written.",
), Refusal {

    override val arguments get() = mapOf("missing" to missing)
}

/**
 * The code and the declared parameters disagree about how many arguments there are.
 *
 * Says both numbers and where the second one comes from, because the mismatch is
 * usually an external somebody added in the panel and did not add to the code —
 * and "expected 2, found 1" on its own does not point at that.
 */
class FunctionSignatureMismatchException(
    found: Int,
    params: Int,
    externals: Int,
) : RuntimeException(
    "The code takes $found ${argument(found)}, but this function is handed " +
        "${params + externals}: $params declared" +
        (if (externals > 0) " and $externals from the workspace" else "") +
        ". They are passed in that order, so the code has to accept all of them.",
) {
    private companion object {
        fun argument(count: Int): String = if (count == 1) "argument" else "arguments"
    }
}

/**
 * A function something still calls is not one to delete.
 *
 * The callers are actions, conditions and the webhooks that authenticate with
 * one. Actions and conditions arrive as bare names because a workspace's lists
 * are what somebody is looking at when they read this; a webhook is said as "the
 * webhook Nightly", because it is not in any of those lists and a bare name
 * would send the reader looking for an action that does not exist.
 */
class FunctionInUseException(val name: String, val callers: List<String>) :
    RuntimeException("$name is called by ${callers.joinToString(", ")}"), Refusal {

    override val arguments get() = mapOf("name" to name, "callers" to callers)
}

/**
 * An import points at nothing, or at something this workspace cannot reach.
 *
 * Answered as "there is no such function" rather than as a refusal, because from
 * where the caller stands there is not: a function in another workspace is not one
 * this one may learn the existence of by being told it exists.
 */
class ImportNotFoundException(val id: Long) : RuntimeException("There is no function $id to import"), Refusal {

    override val arguments get() = mapOf("id" to id)
}

/**
 * A function a plugin declared cannot be imported.
 *
 * Not a rule about tidiness. A plugin's function does not run in this sandbox at
 * all — it runs in the plugin's, constructed from the plugin's source and handed
 * the settings of the workspace calling it — so there is nothing here to import.
 * Nodes call these; code does not.
 */
class ImportNotEditableException(val name: String) : RuntimeException(
    "$name is provided by a plugin, so it cannot be imported. Point an action at it instead.",
), Refusal {

    override val arguments get() = mapOf("name" to name)
}

class ImportNameInvalidException(val name: String) :
    RuntimeException("\"$name\" is not a name an import can be called by"), Refusal {

    override val arguments get() = mapOf("name" to name)
}

/**
 * Two imports answer to the same name.
 *
 * Refused rather than resolved, because the code says the name once and only one
 * of the two could ever answer to it — and which one is whichever the object
 * literal was built with last.
 */
class ImportNameTakenException(val name: String) :
    RuntimeException("This already imports something called \"$name\""), Refusal {

    override val arguments get() = mapOf("name" to name)
}

/**
 * A function imports itself, directly or round a loop.
 *
 * Names the loop rather than saying that there is one: "a imports b imports a" is
 * the sentence somebody can act on, and a cycle three deep is not one anybody
 * finds by being told it exists.
 */
class ImportCycleException(val path: List<String>) : RuntimeException(
    "That import would make a loop: ${path.joinToString(" imports ")}",
), Refusal {

    override val arguments get() = mapOf("path" to path)
}

/**
 * A function something imports is not one to delete.
 *
 * Kept apart from [FunctionInUseException] because the way out is different. A
 * caller is a node somebody points elsewhere; an importer is code somebody has to
 * edit, and telling them to go and change an action would send them to the wrong
 * screen.
 */
class FunctionImportedException(val name: String, val importers: List<String>) :
    RuntimeException("$name is imported by ${importers.joinToString(", ")}"), Refusal {

    override val arguments get() = mapOf("name" to name, "importers" to importers)
}

