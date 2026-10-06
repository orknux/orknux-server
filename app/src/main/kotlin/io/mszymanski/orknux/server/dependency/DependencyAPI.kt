package io.mszymanski.orknux.server.dependency

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller

/**
 * One row of "where is this used", as a screen is told it.
 *
 * [Dependant] without its [Dependant.phrase]: the clause a refusal is assembled
 * from is not something a screen has any use for, and shipping it would be two
 * wordings of the same row for a reader to notice disagreeing.
 */
data class DependantView(
    val kind: DependencyKind,
    val id: Long,
    val name: String,
    val workspaceId: Long?,
    val workspaceName: String?,
    val published: Boolean,
)

/** The rows, and how many there are that this reader may not be told about. */
data class DependantsView(
    val entries: List<DependantView>,
    val hidden: Int,
)

/**
 * Where a component is used — the query behind #258.
 *
 * The list is on the component's own page and every row opens the thing that
 * names it. What it answers is exactly what the delete guard refuses on, because
 * both ask [ComponentDependants]: being shown a list that says a function is used
 * by nothing and then being refused when deleting it would be worse than having
 * no list at all.
 *
 * **Access is decided twice, and differently, on purpose.**
 *
 * Asking at all needs the right to see the component: a workspace-scoped subject
 * answers as not-found for somebody who cannot see its workspace, which is the
 * same answer an id that does not exist gets and for the same reason
 * [WorkspaceAccess.requireVisible] gives it — two answers to one question is a
 * directory.
 *
 * *Naming* an answer needs the right to see the answer's workspace, which is a
 * second question and only ever a real one for a library. A library belongs to
 * the installation and the functions importing it can be anywhere, so a reader
 * with one workspace could otherwise learn the names of every other workspace's
 * code from a screen about `date-fns`. Those rows are counted into
 * [DependantsView.hidden] rather than dropped: naming them is a leak and losing
 * them silently is an answer with rows missing, and a count is neither. An
 * administrator sees every workspace, so on the libraries screen — the one place
 * this matters today — `hidden` is always zero.
 */
@Controller
class DependencyAPI(
    private val dependants: ComponentDependants,
    private val workspaces: WorkspaceRepository,
    private val access: WorkspaceAccess,
) {

    @QueryMapping
    fun componentDependants(
        @Argument kind: DependencyKind,
        @Argument componentId: Long,
    ): DependantsView {
        if (!kind.askable) throw DependencyKindNotAskableException(kind)
        if (!dependants.exists(kind, componentId)) throw ComponentNotFoundException(kind, componentId)
        /*
         * A library belongs to no workspace and is not a secret: everybody with a
         * workspace picks from the same list, so anybody may ask what imports one.
         * What they are told is where the line is drawn, below.
         */
        val workspaceId = dependants.workspaceOf(kind, componentId)
        if (workspaceId != null) access.requireVisible(workspaceId)
        return visible(dependants.of(kind, componentId))
    }

    /**
     * The rows this reader may be told the names of, and a count of the rest.
     *
     * The workspace names are read once rather than once a row, and only for the
     * workspaces the rows are in - not every workspace of the installation with
     * its roles, to name one. A dependant with no workspace at all is an
     * organisation function, which every workspace can already see and reach,
     * so it is named.
     */
    fun visible(found: List<Dependant>): DependantsView {
        val unnamed = found.filter { it.workspaceName == null }.mapNotNull { it.workspaceId }.toSet()
        val named = if (unnamed.isEmpty()) emptyMap() else workspaces.findAllById(unnamed).associate { it.id to it.name }
        val seen = mutableMapOf<Long, Boolean>()
        fun mayName(workspaceId: Long?): Boolean =
            workspaceId == null || seen.getOrPut(workspaceId) { access.canSee(workspaceId) }

        val (shown, hidden) = found.partition { mayName(it.workspaceId) }
        return DependantsView(
            entries = shown.map {
                DependantView(
                    kind = it.kind,
                    id = it.id,
                    name = it.name,
                    workspaceId = it.workspaceId,
                    workspaceName = it.workspaceName ?: named[it.workspaceId],
                    published = it.published,
                )
            },
            hidden = hidden.size,
        )
    }
}

/**
 * Asked about a component that is not there, or is not this reader's to know of.
 *
 * The same answer for both, which is the line
 * [WorkspaceAccess.requireVisible] draws for a workspace and this draws for what
 * is inside one.
 */
class ComponentNotFoundException(kind: DependencyKind, id: Long) :
    RuntimeException("There is no ${kind.label} $id")

@Component
class DependencyExceptionResolver : DataFetcherExceptionResolverAdapter() {

    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? {
        val errorType = when (exception) {
            is ComponentNotFoundException -> ErrorType.NOT_FOUND
            is DependencyKindNotAskableException -> ErrorType.BAD_REQUEST
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
