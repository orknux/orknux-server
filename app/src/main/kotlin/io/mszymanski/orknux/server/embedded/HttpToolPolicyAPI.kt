package io.mszymanski.orknux.server.embedded

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.orknux.server.graphql.refused
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller

/**
 * Admin -> Settings -> HTTP tools. Issue #602.
 *
 * Administrators only, reading as well as writing: where an installation lets
 * its agents go is a statement about its network, and the tester is a way of
 * reading the list one address at a time. Audited as INTEGRATION, beside the
 * proxy rules, since both are about what this server may reach.
 */
@Controller
class HttpToolPolicyAPI(
    private val policy: HttpToolPolicy,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
) {

    @QueryMapping
    fun httpToolSettings(): HttpToolPolicyView {
        access.requireAdmin()
        return policy.current()
    }

    @QueryMapping
    fun httpToolCheck(
        @Argument url: String,
        @Argument method: String,
        @Argument draft: HttpToolPolicyInput?,
    ): HttpToolDecision {
        access.requireAdmin()
        return policy.decide(url.trim(), method, draft?.toDraft())
    }

    @MutationMapping
    fun setHttpToolsEnabled(@Argument enabled: Boolean): HttpToolPolicyView {
        access.requireAdmin()
        policy.setEnabled(enabled, currentUser())
        auditRecorder.record(
            null,
            WorkspaceAuditCategory.INTEGRATION,
            if (enabled) "HTTP tools switched on" else "HTTP tools switched off",
        )
        return policy.current()
    }

    @MutationMapping
    fun saveHttpToolPolicy(@Argument input: HttpToolPolicyInput): HttpToolPolicyView {
        access.requireAdmin()
        val draft = input.toDraft()
        val saved = policy.save(draft.kind, draft.rules, currentUser())
        val said = when (saved.kind) {
            HttpToolPolicyKind.ANY -> "HTTP tools set to allow any URL"
            HttpToolPolicyKind.LIST -> when (saved.rules.size) {
                1 -> "HTTP tools set to an allow list of 1 rule"
                else -> "HTTP tools set to an allow list of ${saved.rules.size} rules"
            }
        }
        auditRecorder.record(null, WorkspaceAuditCategory.INTEGRATION, said)
        return saved
    }

    @SchemaMapping(typeName = "HttpToolSettings")
    fun policy(view: HttpToolPolicyView): HttpToolPolicyKind = view.kind

    @SchemaMapping(typeName = "HttpToolCheck")
    fun matchedRule(decision: HttpToolDecision): Int? = decision.matched?.position

    private fun currentUser(): String =
        SecurityContextHolder.getContext().authentication?.name ?: "system"
}

/** What a screen sends: the policy and its rules, saved or about to be. */
data class HttpToolPolicyInput(
    val policy: HttpToolPolicyKind,
    val rules: List<HttpToolRuleInput>,
) {
    fun toDraft() = HttpToolDraft(policy, rules.map { HttpToolRule(it.url, it.methods) })
}

data class HttpToolRuleInput(val url: String, val methods: List<String>)

@Component
class HttpToolPolicyExceptionResolver : DataFetcherExceptionResolverAdapter() {
    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? =
        when (exception) {
            is HttpToolRulePatternMissingException,
            is HttpToolRulePatternTooLongException,
            is HttpToolRulePatternInvalidException,
            is HttpToolRuleMethodsMissingException,
            is HttpToolRuleMethodUnknownException,
            -> refused(exception, ErrorType.BAD_REQUEST, environment)

            else -> null
        }
}
