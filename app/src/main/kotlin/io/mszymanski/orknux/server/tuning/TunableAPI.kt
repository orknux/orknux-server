package io.mszymanski.orknux.server.tuning

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.orknux.server.graphql.refused
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller

/**
 * Admin -> Settings -> the numbers the server behaves by. Issue #453.
 *
 * Administrators only, reading as well as writing: there is nothing on this
 * list a person outside Admin acts on, and a limit is also a description of
 * where the edges are.
 */
@Controller
class TunableAPI(
    private val tunables: Tunables,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
) {

    @QueryMapping
    fun tunables(): List<TunableView> {
        access.requireAdmin()
        return Tunable.entries.map(::view)
    }

    /** Sets one, or with no value hands it back to the configuration file. */
    @MutationMapping
    fun setTunable(@Argument key: String, @Argument value: Int?): TunableView {
        access.requireAdmin()
        val tunable = Tunable.byKey(key) ?: throw TunableUnknownException(key)
        tunables.set(tunable, value, currentUser())
        val now = tunables[tunable]
        auditRecorder.record(
            null,
            WorkspaceAuditCategory.WORKSPACE,
            if (value == null) {
                "${tunable.label}: back to the configured $now ${tunable.unit.said}"
            } else {
                "${tunable.label}: set to $now ${tunable.unit.said}"
            },
        )
        return view(tunable)
    }

    private fun view(tunable: Tunable) = TunableView(
        key = tunable.key,
        section = tunable.section.name,
        sectionTitle = tunable.section.title,
        label = tunable.label,
        hint = tunable.hint,
        unit = tunable.unit.said,
        value = tunables[tunable],
        configured = tunables.configured(tunable),
        set = tunables.isSet(tunable),
        least = tunable.least,
        most = tunable.most,
        variable = tunable.variable,
    )

    private fun currentUser(): String =
        SecurityContextHolder.getContext().authentication?.name ?: "system"
}

data class TunableView(
    val key: String,
    val section: String,
    val sectionTitle: String,
    val label: String,
    val hint: String,
    val unit: String,
    val value: Int,
    val configured: Int,
    val set: Boolean,
    val least: Int,
    /** Null where there is no upper bound. */
    val most: Int?,
    val variable: String,
)

@Component
class TunableExceptionResolver : DataFetcherExceptionResolverAdapter() {
    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? =
        when (exception) {
            is TunableUnknownException -> refused(exception, ErrorType.NOT_FOUND, environment)
            is TunableOutOfRangeException -> refused(exception, ErrorType.BAD_REQUEST, environment)
            else -> null
        }
}
