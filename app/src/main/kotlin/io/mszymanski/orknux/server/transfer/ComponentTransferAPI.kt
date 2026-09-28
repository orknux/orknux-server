package io.mszymanski.orknux.server.transfer

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.orknux.server.security.WorkspaceAccess
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller

/**
 * Export and import for the components a workspace can move between installations.
 *
 * The envelope crosses this boundary as a string rather than as a GraphQL type,
 * and deliberately: it is a file format, versioned by the integer inside it, and
 * a second description of it in the schema would be a second thing to keep in
 * step. What the schema does describe is the *plan* — which is not the format,
 * but this installation's answer about one file and one workspace.
 *
 * Both directions check the workspace first. An import is a write, so it is
 * checked like one: the same `requireVisible` an editor's save goes through.
 *
 * The binding step is not a third call. A file that names a model or a
 * connection is described by the plan exactly as one that names a missing
 * object is — kind, name, and what has to happen — so asking what needs binding
 * is asking for the plan, and answering is asking for the plan again with the
 * answers attached. A call of its own that only listed the unbound would be a
 * second reader of the same file, and the lenient one: it could offer a form for
 * a file the import then refuses for some other reason entirely.
 *
 * Leaving a component out takes the same route for the same reason. Naming one
 * the file carries asks for the plan again without it, and what that costs — a
 * kept component that needed it going too, a reference that now has nothing to
 * point at — is in the plan that comes back, before anything is written. Only
 * what the file carries can be named: a plan lists what the envelope points at
 * beside what it holds, and a reference is a mention rather than a thing.
 */
@Controller
class ComponentTransferAPI(
    private val exporter: ComponentExporter,
    private val importer: ComponentImporter,
    private val access: WorkspaceAccess,
    private val audit: io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder,
) {

    /**
     * A copy of one component in its own workspace, under the next free name -
     * "Triage (2)", or `normalise_2` for a name code calls.
     *
     * Exported shallow and imported back: the copy is the component alone, and
     * everything it points at - an action's function, an agent's model and
     * tools - is the same one the original points at, because those are found
     * here by name. The export and import already decide every field, so a
     * duplicate carries exactly what moving it to another workspace would.
     */
    @MutationMapping
    fun duplicateComponent(@Argument workspaceId: Long, @Argument kind: ComponentKind, @Argument id: Long): DuplicatedComponent {
        access.requireVisible(workspaceId)
        val from = exporter.fileNameFor(workspaceId, kind, id)
        val envelope = exporter.export(workspaceId, kind, id, ExportDepth.SHALLOW)
        val done = importer.apply(workspaceId, envelope)
        val made = done.entries.first { it.carried && it.kind == kind }
        audit.record(workspaceId, categoryOf(kind), "${kind.label.replaceFirstChar { it.uppercase() }} ${made.name} duplicated as ${made.targetName}")
        return DuplicatedComponent(kind = kind, name = made.targetName)
    }

    private fun categoryOf(kind: ComponentKind) = when (kind) {
        ComponentKind.AGENT, ComponentKind.SKILL, ComponentKind.TOOL -> io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory.AGENT
        ComponentKind.OBJECT -> io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory.OBJECT
        else -> io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory.WORKFLOW
    }

    /** What the Export control downloads: a file name and the JSON to put in it. */
    @QueryMapping
    fun exportComponent(
        @Argument workspaceId: Long,
        @Argument kind: ComponentKind,
        @Argument id: Long,
        @Argument depth: ExportDepth?,
    ): ComponentExportView {
        access.requireVisible(workspaceId)
        val chosen = depth ?: ExportDepth.DEEP
        return ComponentExportView(
            fileName = exporter.fileNameFor(workspaceId, kind, id),
            json = exporter.export(workspaceId, kind, id, chosen),
        )
    }

    /**
     * What an import would do, before it does it.
     *
     * A query rather than a mutation because it writes nothing — but it takes
     * the whole file, so it is checked against the workspace exactly as the
     * mutation is. Somebody who cannot see a workspace cannot find out what it
     * already holds by asking what would collide.
     */
    @QueryMapping
    fun componentImportPlan(
        @Argument workspaceId: Long,
        @Argument envelope: String,
        @Argument bindings: List<ComponentBinding>?,
        @Argument exclude: List<ComponentExclusion>?,
        @Argument rename: List<ComponentRename>?,
    ): ImportPlan {
        access.requireVisible(workspaceId)
        return importer.plan(workspaceId, envelope, bindings.orEmpty(), exclude.orEmpty(), rename.orEmpty())
    }

    /**
     * Imports the file, or nothing at all.
     *
     * Answers the same plan the preview showed, filled in with what was actually
     * done — so a client that skipped the preview still gets told what it caused,
     * and one that showed it can compare.
     */
    @MutationMapping
    fun importComponents(
        @Argument workspaceId: Long,
        @Argument envelope: String,
        @Argument bindings: List<ComponentBinding>?,
        @Argument exclude: List<ComponentExclusion>?,
        @Argument rename: List<ComponentRename>?,
    ): ImportPlan {
        access.requireVisible(workspaceId)
        return importer.apply(workspaceId, envelope, bindings.orEmpty(), exclude.orEmpty(), rename.orEmpty())
    }
}

/** What a duplicate came to: its kind and the name it arrived under. */
data class DuplicatedComponent(val kind: ComponentKind, val name: String)

/** The download: the suggested name, and the bytes. */
data class ComponentExportView(val fileName: String, val json: String)

@Component
class ComponentTransferExceptionResolver : DataFetcherExceptionResolverAdapter() {

    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? {
        val errorType = when (exception) {
            is EnvelopeUnreadableException,
            is EnvelopeVersionUnknownException,
            is EnvelopeInvalidException,
            is ImportNotPossibleException,
            is ImportBindingInvalidException,
            is ImportExclusionUnknownException,
            is ImportRenameUnknownException,
            -> ErrorType.BAD_REQUEST

            is ComponentNotExportableException -> ErrorType.NOT_FOUND

            else -> return null
        }

        return GraphQLError.newError()
            .errorType(errorType)
            .message(exception.message)
            .path(environment.executionStepInfo.path)
            .location(environment.field.sourceLocation)
            .build()
    }
}
