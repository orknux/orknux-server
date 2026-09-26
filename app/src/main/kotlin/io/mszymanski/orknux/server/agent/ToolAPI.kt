package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.server.action.FunctionExternal
import io.mszymanski.orknux.server.action.FunctionExternalView
import io.mszymanski.orknux.server.action.ImportNameInvalidException
import io.mszymanski.orknux.server.action.ImportNameTakenException
import io.mszymanski.orknux.server.action.ImportNotFoundException
import io.mszymanski.orknux.server.action.ImportedFunctionView
import io.mszymanski.orknux.server.action.ScriptImport
import io.mszymanski.orknux.server.action.ScriptImportInput
import io.mszymanski.orknux.server.action.ScriptImportView
import io.mszymanski.orknux.server.action.ScriptImports
import io.mszymanski.orknux.server.action.ScriptLibraryImportInput
import io.mszymanski.orknux.server.action.ScriptLibraryImportView
import io.mszymanski.orknux.server.action.ValueType
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.action.typeScriptType
import io.mszymanski.orknux.server.dependency.ComponentDependants
import io.mszymanski.orknux.server.dependency.DependencyKind
import io.mszymanski.orknux.server.dependency.phrases
import io.mszymanski.orknux.server.library.LibraryImports
import io.mszymanski.orknux.server.obj.ObjectNotFoundException
import io.mszymanski.orknux.server.obj.WorkflowObjectRepository
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.variable.VariableNotFoundException
import io.mszymanski.orknux.server.variable.WorkspaceVariableRepository
import io.mszymanski.orknux.server.workspace.MAX_SCRIPT_TIMEOUT_SECONDS
import io.mszymanski.orknux.server.workspace.MIN_SCRIPT_TIMEOUT_SECONDS
import io.mszymanski.orknux.server.workspace.ScriptTimeoutOutOfRangeException
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.server.workspace.pageRequest
import io.mszymanski.orknux.server.workspace.sortBy
import io.mszymanski.orknux.workflow.script.ScriptArity
import io.mszymanski.orknux.workflow.script.ScriptRunner
import org.springframework.data.domain.Page
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import io.mszymanski.orknux.server.revision.ComponentRevisionKind
import io.mszymanski.orknux.server.revision.ComponentRevisionRecorder
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * A workspace's tools: the JavaScript its agents may call while they run.
 *
 * The source is never run here except to be parsed — `validateToolSource` backs
 * the editor's Validate — and even that happens in the sandbox, so a script
 * cannot reach anything by being compiled.
 */
@Controller
class ToolAPI(
    private val tools: AgentToolRepository,
    private val scripts: ScriptRunner,
    private val workspaces: WorkspaceRepository,
    private val objects: WorkflowObjectRepository,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
    private val revisions: ComponentRevisionRecorder,
    private val dependants: ComponentDependants,
    private val functions: WorkflowFunctionRepository,
    private val scriptImports: ScriptImports,
    private val libraryImports: LibraryImports,
    private val variables: WorkspaceVariableRepository,
) {

    /** The columns this list can be put in the order of. Issue #358. */
    private val TOOL_ORDERS = mapOf(
        "NAME" to listOf("name"),
        "DESCRIPTION" to listOf("description", "name"),
        "STATUS" to listOf("enabled", "name"),
        "LAST_MODIFIED" to listOf("lastModifiedAt"),
    )

    @QueryMapping
    /** @param search what to look for in this list, or null for all of it. */
    fun workspaceTools(
        @Argument workspaceId: Long,
        @Argument page: Int?,
        @Argument size: Int?,
        @Argument search: String?,
        @Argument order: String?,
        @Argument ascending: Boolean?,
    ): ToolPage {
        requireWorkspaceAccess(workspaceId)
        val paged = pageRequest(page, size, sortBy(order, ascending, TOOL_ORDERS, "NAME"))
        val looking = search?.trim().orEmpty()

        return ToolPage(
            if (looking.isEmpty()) {
                tools.findByWorkspaceId(workspaceId, paged)
            } else {
                tools.searching(workspaceId, looking, paged)
            },
            ::describe,
        )
    }

    @QueryMapping
    fun tool(@Argument id: Long): ToolView? {
        val tool = tools.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) } ?: return null
        return describe(tool)
    }

    @MutationMapping
    @Transactional
    fun createTool(@Argument input: CreateToolInput): ToolView {
        requireWorkspaceAccess(input.workspaceId)
        val name = input.name.trim()
        if (!IDENTIFIER.matches(name)) throw ToolNameInvalidException(name)
        if (tools.findByWorkspaceIdAndName(input.workspaceId, name) != null) throw ToolNameTakenException(name)

        /*
         * A tool that says nothing about what it takes takes what every tool used
         * to take: one object called `input`. Left as a default rather than as an
         * empty list, so a caller written before parameters existed - the MCP
         * tools, a duplicate, a test - still creates a tool an agent can call.
         */
        val params = (input.params ?: listOf(DEFAULT_PARAM)).toParams(input.workspaceId)
        val externals = input.externalVariableIds.orEmpty().toExternals(input.workspaceId)
        val code = codeFrom(input.source, input.typescript) ?: starter(name, params)
        requireParses(code.javascript)
        requireSignature(code.javascript, params.size, externals.size)

        val tool = tools.save(
            AgentTool(
                workspaceId = input.workspaceId,
                name = name,
                description = input.description?.trim()?.ifEmpty { null }?.also(::requireDescriptionFits),
                summary = input.summary?.trim()?.ifEmpty { null }?.also(::requireSummaryFits),
                source = code.javascript,
                typescript = code.typescript,
                params = params,
                externals = externals,
                imports = input.imports.orEmpty().toImports(input.workspaceId),
                libraries = input.libraries.orEmpty().toLibraries(),
                lastModifiedAt = OffsetDateTime.now(),
                lastModifiedBy = currentUser(),
            ),
        )
        auditRecorder.record(input.workspaceId, WorkspaceAuditCategory.AGENT, "Tool $name created")
        return describe(tool)
    }

    /** Backs the editor: the code on the left, the details on the right. */
    @MutationMapping
    @Transactional
    fun updateTool(@Argument id: Long, @Argument input: UpdateToolInput): ToolView {
        val tool = tools.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) } ?: throw ToolNotFoundException(id)

        // What it is about to stop being. A tool has no draft, so a save is a
        // version; the recorder holds that rule, this door only reports.
        revisions.saved(tool)

        val previousName = tool.name
        input.name?.trim()?.let { name ->
            if (!IDENTIFIER.matches(name)) throw ToolNameInvalidException(name)
            if (name != tool.name && tools.findByWorkspaceIdAndName(tool.workspaceId, name) != null) {
                throw ToolNameTakenException(name)
            }
            tool.name = name
        }
        input.description?.let { tool.description = it.trim().ifEmpty { null }?.also(::requireDescriptionFits) }
        input.summary?.let { tool.summary = it.trim().ifEmpty { null }?.also(::requireSummaryFits) }
        /*
         * Both halves or neither. A write that moved one would leave the editor
         * showing code the sandbox is not running, which is the one failure this
         * pair exists to prevent.
         */
        codeFrom(input.source, input.typescript)?.let { code ->
            requireParses(code.javascript)
            tool.source = code.javascript
            tool.typescript = code.typescript
        }
        /*
         * Null leaves them alone, an empty list takes them all off - the same
         * bargain a function's parameters are saved under, so a client that only
         * meant to rename a tool does not have to resend its signature to keep it.
         */
        input.params?.let { tool.params = it.toParams(tool.workspaceId) }
        input.externalVariableIds?.let { tool.externals = it.toExternals(tool.workspaceId) }
        input.imports?.let { tool.imports = it.toImports(tool.workspaceId) }
        input.libraries?.let { tool.libraries = it.toLibraries() }

        /*
         * Checked against what this tool will be once saved, not against whichever
         * field happened to arrive: adding a parameter without touching the code
         * breaks the contract exactly as much as editing the code does.
         */
        requireSignature(tool.source, tool.params.size, tool.externals.size)

        tool.lastModifiedAt = OffsetDateTime.now()
        tool.lastModifiedBy = currentUser()

        val message = if (previousName == tool.name) {
            "Tool ${tool.name} updated"
        } else {
            "Tool $previousName renamed to ${tool.name}"
        }
        auditRecorder.record(tool.workspaceId, WorkspaceAuditCategory.AGENT, message)
        return describe(tool)
    }

    /** The toggle on the list: out of reach, but still there. */
    @MutationMapping
    @Transactional
    fun setToolEnabled(@Argument id: Long, @Argument enabled: Boolean): ToolView {
        val tool = tools.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) } ?: throw ToolNotFoundException(id)

        // The toggle is a save like any other: it changes what the workspace
        // has, and a version of it is what it was a moment ago.
        revisions.saved(tool)
        tool.enabled = enabled
        tool.lastModifiedAt = OffsetDateTime.now()
        tool.lastModifiedBy = currentUser()
        val what = if (enabled) "enabled" else "disabled"
        auditRecorder.record(tool.workspaceId, WorkspaceAuditCategory.AGENT, "Tool ${tool.name} $what")
        return describe(tool)
    }

    /**
     * How long one call of this tool may run.
     *
     * Its own mutation rather than a field on the update, for the same reason
     * the workspace's default has one: null is a real answer — "back on the
     * workspace's number" — and the update input reads null as "leave it
     * alone". Read per call, so this decides the next call and leaves one
     * already running alone.
     */
    @MutationMapping
    @Transactional
    fun setToolTimeout(@Argument id: Long, @Argument seconds: Int?): ToolView {
        val tool = tools.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) } ?: throw ToolNotFoundException(id)

        if (seconds != null && seconds !in MIN_SCRIPT_TIMEOUT_SECONDS..MAX_SCRIPT_TIMEOUT_SECONDS) {
            throw ScriptTimeoutOutOfRangeException(seconds)
        }

        // A save like any other: it changes what the next call is given.
        revisions.saved(tool)
        tool.timeoutSeconds = seconds
        tool.lastModifiedAt = OffsetDateTime.now()
        tool.lastModifiedBy = currentUser()
        auditRecorder.record(
            tool.workspaceId,
            WorkspaceAuditCategory.AGENT,
            seconds?.let { "Tool ${tool.name} may run for $it seconds" }
                ?: "Tool ${tool.name} runs on the workspace's time again",
        )
        return describe(tool)
    }

    /**
     * The editor's Validate: parses the source and says where it broke.
     *
     * It answers rather than throws, because a syntax error is what the button
     * is for, not a failed request.
     */
    @MutationMapping
    fun validateToolSource(@Argument workspaceId: Long, @Argument source: String): SourceValidationView {
        requireWorkspaceAccess(workspaceId)
        val checked = scripts.validate(source)
        return SourceValidationView(checked.valid, checked.message, checked.line, checked.column)
    }

    /**
     * Removes a tool, unless an agent was granted it.
     *
     * A grant is a name rather than an id, so nothing here would have been left
     * dangling: the agent would simply have stopped being able to do this, with
     * its own screen still listing the grant and nothing anywhere saying what
     * changed. [AgentGrants] is where that argument is written down.
     */
    @MutationMapping
    @Transactional
    fun deleteTool(@Argument id: Long): Boolean {
        val tool = tools.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) } ?: return false

        val granted = dependants.of(DependencyKind.TOOL, id)
        if (granted.isNotEmpty()) throw ToolInUseException(tool.name, granted.phrases())

        tools.delete(tool)
        revisions.forget(ComponentRevisionKind.TOOL, id)
        auditRecorder.record(tool.workspaceId, WorkspaceAuditCategory.AGENT, "Tool ${tool.name} deleted")
        return true
    }

    private fun describe(tool: AgentTool): ToolView {
        val params = tool.params.map { param ->
            ToolParamView(
                name = param.name,
                type = param.type,
                objectId = param.objectId,
                objectName = param.objectId?.let { objects.findByIdOrNull(it)?.name },
            )
        }
        val externals = tool.externals.mapNotNull { held ->
            variables.findByIdOrNull(held.variableId)?.let { variable ->
                FunctionExternalView(
                    variableId = requireNotNull(variable.id),
                    name = variable.name,
                    type = variable.type,
                )
            }
        }
        return ToolView(
        id = requireNotNull(tool.id),
        workspaceId = tool.workspaceId,
        name = tool.name,
        description = tool.description,
        summary = tool.summary,
        source = tool.source,
        typescript = tool.typescript,
        params = params,
        externals = externals,
        timeoutSeconds = tool.timeoutSeconds,
        imports = tool.imports.map { imported ->
            ScriptImportView(
                functionId = imported.importedId,
                name = imported.importName,
                function = functions.findByIdOrNull(imported.importedId)?.let { target ->
                    ImportedFunctionView(
                        name = target.name,
                        description = target.description,
                        signature = target.signature,
                        returnType = target.returnType,
                        returnObjectName = target.returnObjectId?.let { objects.findByIdOrNull(it)?.name },
                    )
                },
            )
        },
        libraries = tool.libraries.map { imported ->
            ScriptLibraryImportView(
                libraryId = imported.importedId,
                name = imported.importName,
                library = libraryImports.find(imported.importedId)?.let(libraryImports::viewOf),
            )
        },
        signature = signatureOf(params, externals),
        enabled = tool.enabled,
        lastModifiedAt = tool.lastModifiedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        lastModifiedBy = tool.lastModifiedBy,
        )
    }

    /**
     * The two halves marked apart, the way a function's signature says it: the
     * caller fills the declared ones, the workspace supplies the rest, and
     * somebody reading the list needs to know which is which.
     */
    private fun signatureOf(params: List<ToolParamView>, externals: List<FunctionExternalView>): String {
        val declared = params.map { "${it.name}: ${it.objectName ?: it.type.name.lowercase()}" }
        val handed = externals.map { "${it.name}: ${it.type.name.lowercase()} (external)" }
        return (declared + handed).joinToString(", ", "(", ")")
    }

    /**
     * The variables this tool is to be handed, checked against the workspace
     * that owns it: a tool cannot be given another workspace's secret by id.
     */
    private fun List<Long>.toExternals(workspaceId: Long): MutableList<FunctionExternal> = distinct()
        .map { variableId ->
            val variable = variables.findByIdOrNull(variableId) ?: throw VariableNotFoundException(variableId)
            if (variable.workspaceId != workspaceId) throw VariableNotFoundException(variableId)
            FunctionExternal(variableId = variableId)
        }
        .toMutableList()

    private fun requireParses(source: String) {
        val checked = scripts.validate(source)
        if (!checked.valid) throw ToolSourceInvalidException(checked.message ?: "The script could not be parsed")
    }

    /**
     * Refuses code that cannot be called the way it will be called.
     *
     * The sandbox passes the declared parameters positionally, so a tool whose
     * code takes three arguments while its details declare one is not a tool
     * that works — the model fills the one declared parameter and the code
     * reads it as its first argument, whatever that argument was meant to be.
     * The same check a function is saved under.
     */
    private fun requireSignature(source: String, params: Int, externals: Int) {
        val expected = params + externals
        when (val counted = scripts.arity(source)) {
            is ScriptArity.Counted ->
                if (counted.parameters != expected) {
                    throw ToolSignatureMismatchException(counted.parameters, params, externals)
                }

            // No default export, or not a function: it could never have run.
            is ScriptArity.Unreadable -> throw ToolSourceInvalidException(counted.reason)
        }
    }

    /**
     * What a new tool starts as: something that parses, and that says in a
     * comment what the agent will be reading when it decides to call it.
     */
    /**
     * What a new tool starts as: a declaration that already takes what the tool
     * says it takes.
     *
     * Printed twice from one list, the way a function's stub is: the TypeScript
     * is what somebody opens and the JavaScript is what runs, and the only
     * difference between them is the annotations. They are not two versions that
     * could drift.
     */
    private fun starter(name: String, params: List<AgentToolParam>): ToolCode {
        fun body(arguments: String) = """
            export default async function $name($arguments) {
              // What this returns is handed back to the agent that called it.
              return { ok: true };
            }
        """.trimIndent()

        val annotated = params.joinToString(", ") {
            "${it.name}: ${typeScriptType(it.type, it.objectId?.let { id -> objects.findByIdOrNull(id)?.name })}"
        }
        return ToolCode(javascript = body(params.joinToString(", ") { it.name }), typescript = body(annotated))
    }

    /**
     * The stored parameters, from what arrived.
     *
     * Names are checked the way a function's are - a parameter the sandbox
     * cannot bind is not a parameter - and so is uniqueness, which a function
     * does not have to check because it passes its arguments positionally and
     * nothing addresses them by name. A tool's are addressed by name, by the
     * model, so two alike is a hole rather than a curiosity.
     */
    /**
     * The functions this tool is to import, checked before they are stored.
     *
     * The same four questions a function's imports are asked, and asked by the same
     * class - a tool importing a function is the same arrangement, and a second copy
     * of the rules here would be a second copy to keep in step. There is no loop to
     * look for: nothing imports a tool, so a tool cannot be in one.
     */
    private fun List<ScriptImportInput>.toImports(workspaceId: Long): MutableList<ScriptImport> {
        val taken = mutableSetOf<String>()
        return map { asked ->
            val name = asked.name.trim()
            if (!IDENTIFIER.matches(name)) throw ImportNameInvalidException(name)
            if (!taken.add(name)) throw ImportNameTakenException(name)

            val imported = functions.findByIdOrNull(asked.functionId) ?: throw ImportNotFoundException(asked.functionId)
            scriptImports.requireImportable(imported, workspaceId, importer = null)
            ScriptImport(importedId = asked.functionId, importName = name)
        }.toMutableList()
    }

    /**
     * The libraries this script is to import, checked before they are stored.
     *
     * The same two questions an imported function is asked about its name, and one
     * fewer about itself: every loaded library is offerable to every workspace, so
     * there is nothing to compare a workspace against, and a library imports
     * nothing, so there is no loop to look for.
     */
    private fun List<ScriptLibraryImportInput>.toLibraries(): MutableList<ScriptImport> {
        val taken = mutableSetOf<String>()
        return map { asked ->
            val name = asked.name.trim()
            if (!IDENTIFIER.matches(name)) throw ImportNameInvalidException(name)
            if (!taken.add(name)) throw ImportNameTakenException(name)

            libraryImports.require(asked.libraryId)
            ScriptImport(importedId = asked.libraryId, importName = name)
        }.toMutableList()
    }

    private fun List<ToolParamInput>.toParams(workspaceId: Long): MutableList<AgentToolParam> {
        val seen = mutableSetOf<String>()
        return map { param ->
            val name = param.name.trim()
            if (!IDENTIFIER.matches(name)) throw ToolParamInvalidException(name)
            if (!seen.add(name)) throw ToolParamDuplicateException(name)
            AgentToolParam(
                name = name,
                type = param.type,
                // Only an object parameter keeps one. Anything else is cleared
                // rather than carried: a stale id under a string is one that
                // comes back the day the type changes again.
                objectId = if (param.type == ValueType.OBJECT) {
                    requireObject(param.objectId, workspaceId, name)
                } else {
                    null
                },
            )
        }.toMutableList()
    }

    /**
     * The object a parameter names, checked against the workspace claiming it.
     *
     * An id from another workspace is answered as though it does not exist,
     * because from where the caller stands it does not.
     */
    private fun requireObject(objectId: Long?, workspaceId: Long, param: String): Long {
        val id = objectId ?: throw ToolObjectRequiredException(param)
        val found = objects.findByIdOrNull(id) ?: throw ObjectNotFoundException(id)
        if (found.workspaceId != workspaceId) throw ObjectNotFoundException(id)
        return id
    }

    /**
     * The pair, from what arrived — or null when neither half was sent, which
     * means "leave the code alone" on an update and "start from a stub" on a
     * create.
     */
    private fun codeFrom(javascript: String?, typescript: String?): ToolCode? {
        val compiled = javascript?.takeIf { it.isNotBlank() }
        val written = typescript?.takeIf { it.isNotBlank() }
        return when {
            compiled != null && written != null -> ToolCode(javascript = compiled, typescript = written)
            compiled != null -> throw ToolCodeIncompleteException("TypeScript this JavaScript was compiled from")
            written != null -> throw ToolCodeIncompleteException("JavaScript compiled from this TypeScript")
            else -> null
        }
    }

    private fun currentUser(): String =
        SecurityContextHolder.getContext().authentication?.name ?: "system"

    private fun requireWorkspaceAccess(workspaceId: Long) {
        access.requireVisible(workspaceId)
    }

    /** The line in the briefing, held to the column. Issue #481. */
    private fun requireSummaryFits(summary: String) {
        if (summary.length > SUMMARY_LIMIT) throw ToolSummaryTooLongException(summary.length, SUMMARY_LIMIT)
    }

    private fun requireDescriptionFits(description: String) {
        if (description.length > DESCRIPTION_LIMIT) {
            throw ToolDescriptionTooLongException(description.length, DESCRIPTION_LIMIT)
        }
    }

    private companion object {
        /** A name JavaScript can call: what the source is written against. */
        val IDENTIFIER = Regex("[A-Za-z_$][A-Za-z0-9_$]{0,63}")

        /** As much description as the column holds. Checked here so going over is a refusal, not an internal error. */
        const val DESCRIPTION_LIMIT = 4000

        /**
         * And as much summary. Fifty characters is a phrase, which is the
         * point: every tool the agent holds is listed in its briefing, and a
         * list of sentences is a list nobody can afford. Issue #481.
         */
        const val SUMMARY_LIMIT = MOST_TOOL_SUMMARY_CHARS

        /**
         * What a tool takes when nobody said: one object, called `input`.
         *
         * The signature every tool had before they had signatures. Kept as the
         * default so the description an agent reads - "it takes whatever it
         * needs in `input`" - goes on being true for a tool created without one.
         */
        val DEFAULT_PARAM = ToolParamInput(name = "input", type = ValueType.MAP)
    }
}

/** One argument a tool takes, as the editor sends it. */
data class ToolParamInput(
    val name: String,
    val type: ValueType,
    /** Required when the type is OBJECT, and ignored otherwise. */
    val objectId: Long? = null,
)

data class CreateToolInput(
    val workspaceId: Long,
    val name: String,
    val description: String? = null,
    /** The line it gets in an agent's briefing, 50 characters at most. Issue #481. */
    val summary: String? = null,
    /** Both left out for a new tool, which starts from a stub that parses. */
    val source: String? = null,
    val typescript: String? = null,
    /** Left out means the one every tool used to take: an object called `input`. */
    val params: List<ToolParamInput>? = null,
    /** The workspace's variables it is handed, after the parameters it declares. */
    val externalVariableIds: List<Long>? = null,
    /** The workspace's functions it calls, under the names it calls them. */
    val imports: List<ScriptImportInput>? = null,
    /** The installation's libraries it uses, under the names it uses them by. */
    val libraries: List<ScriptLibraryImportInput>? = null,
)

data class UpdateToolInput(
    val name: String? = null,
    val description: String? = null,
    /** Null leaves it alone; an empty string clears it. Issue #481. */
    val summary: String? = null,
    val source: String? = null,
    val typescript: String? = null,
    /** Null leaves them alone; an empty list takes them all off. */
    val params: List<ToolParamInput>? = null,
    /** Null leaves them alone; an empty list takes them all off. */
    val externalVariableIds: List<Long>? = null,
    /** Null leaves them alone; an empty list takes them all off. */
    val imports: List<ScriptImportInput>? = null,
    /** Null leaves them alone; an empty list takes them all off. */
    val libraries: List<ScriptLibraryImportInput>? = null,
)

data class ToolParamView(
    val name: String,
    val type: ValueType,
    /** Which object, when the type is OBJECT. Null otherwise. */
    val objectId: Long?,
    /** What that object is called, resolved here for the editor's annotations. */
    val objectName: String?,
)

/** What runs, and what it was written as. Saved together, always. */
data class ToolCode(val javascript: String, val typescript: String)

data class ToolView(
    val id: Long,
    val workspaceId: Long,
    val name: String,
    val description: String?,
    /** The line it gets in an agent's briefing, or null where nobody wrote one. Issue #481. */
    val summary: String?,
    val source: String,
    val typescript: String,
    val params: List<ToolParamView>,
    /** The workspace's variables it is handed, after the parameters it declares. */
    val externals: List<FunctionExternalView>,
    /** How long one call may run, in seconds. Null means the workspace decides. */
    val timeoutSeconds: Int?,
    /** What it imports, and what it calls each of them. */
    val imports: List<ScriptImportView>,
    /** The libraries it uses, and what it calls each of them. */
    val libraries: List<ScriptLibraryImportView>,
    /** "(city: string, days: number)", ready for the list. */
    val signature: String,
    val enabled: Boolean,
    val lastModifiedAt: String,
    val lastModifiedBy: String,
)

/** Shared by the tool and skill editors: both have a Validate button. */
data class SourceValidationView(
    val valid: Boolean,
    val message: String?,
    val line: Int?,
    val column: Int?,
)

data class ToolPage(
    val content: List<ToolView>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
) {
    constructor(page: Page<AgentTool>, describe: (AgentTool) -> ToolView) : this(
        content = page.content.map(describe),
        page = page.number,
        size = page.size,
        totalElements = page.totalElements.toInt(),
        totalPages = page.totalPages,
    )
}
