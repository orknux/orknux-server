package io.mszymanski.orknux.server.model

import io.mszymanski.orknux.connector.model.CreateModelInput
import io.mszymanski.orknux.connector.model.CreateProviderInput
import io.mszymanski.orknux.connector.model.DiscoveredModelView
import io.mszymanski.orknux.connector.model.LlmModelView
import io.mszymanski.orknux.connector.model.ModelProviderView
import io.mszymanski.orknux.connector.model.ModelQuotasInput
import io.mszymanski.orknux.connector.model.ModelService
import io.mszymanski.orknux.connector.model.ModelThrottleInput
import io.mszymanski.orknux.connector.model.ModelUsageView
import io.mszymanski.orknux.connector.model.ResetInterval
import io.mszymanski.orknux.connector.model.UpdateModelInput
import io.mszymanski.orknux.connector.model.UpdateProviderInput
import io.mszymanski.orknux.server.graphql.Refusal
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The workspace's LLM providers and the models it reaches through them.
 *
 * The connection module holds them, for the same reason it holds MCP servers:
 * a provider carries a key. This checks access, calls the module and writes the
 * audit entry, in that order.
 */
@Controller
class ModelAPI(
    private val models: ModelService,
    private val workspaces: WorkspaceRepository,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
) {

    @QueryMapping
    fun modelProviders(@Argument workspaceId: Long): List<ModelProviderView> {
        requireWorkspaceAccess(workspaceId)
        return models.providers(workspaceId)
    }

    @QueryMapping
    fun modelProvider(@Argument id: Long): ModelProviderView? =
        models.provider(id)?.takeIf { access.canSee(it.workspaceId) }

    @QueryMapping("models")
    fun workspaceModels(@Argument workspaceId: Long): List<LlmModelView> {
        requireWorkspaceAccess(workspaceId)
        return models.models(workspaceId)
    }

    @QueryMapping
    fun model(@Argument id: Long): LlmModelView? =
        models.model(id)?.takeIf { access.canSee(it.workspaceId) }

    /**
     * A query, because asking a provider what it offers writes nothing: the
     * catalogue only changes when somebody adds one of the answers.
     */
    @QueryMapping
    fun discoveredModels(@Argument providerId: Long): List<DiscoveredModelView> {
        // Another workspace's provider is answered as one that does not exist,
        // since a list cannot be null and a refusal would confirm the id is real.
        models.provider(providerId)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw ModelProviderNotFoundException(providerId)
        return models.discoverModels(providerId)
    }

    /**
     * What a model was used for, over the last `days` or over a range.
     *
     * The range wins where one was given, and `days` stays what the page opens
     * on. Every question anybody brings to this page is about a window that is
     * not the last thirty days - what yesterday's run cost, what was spent last
     * month, whether the spike on the 14th was this model - and until there were
     * dates none of them could be asked.
     */
    @QueryMapping
    fun modelUsage(
        @Argument id: Long,
        @Argument days: Int,
        @Argument from: String?,
        @Argument to: String?,
    ): ModelUsageResponse {
        // The same reason as `discoveredModels`: usage is not nullable, so a model
        // the caller cannot see is missing rather than forbidden.
        models.model(id)?.takeIf { access.canSee(it.workspaceId) } ?: throw ModelNotFoundException(id)
        return ModelUsageResponse(models.usage(id, days, day(from, "from"), day(to, "to")))
    }

    /**
     * One end of the range, as the rest of this schema says a date.
     *
     * A string because there is no date scalar here and the view answers in
     * strings, so taking one is the shape the client already reads back. A
     * blank one is nothing asked for, which is what an emptied box sends;
     * anything that is not a date is refused by name, because a window silently
     * falling back to the last thirty days would be a chart quietly answering a
     * different question.
     */
    private fun day(said: String?, which: String): LocalDate? {
        val trimmed = said?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { LocalDate.parse(trimmed) }.getOrElse { throw UsageRangeInvalidException(which, trimmed) }
    }

    @MutationMapping
    fun createModelProvider(@Argument input: CreateProviderInput): ModelProviderView {
        requireWorkspaceAccess(input.workspaceId)
        val created = models.createProvider(input)
        auditRecorder.record(input.workspaceId, WorkspaceAuditCategory.MODEL, "Provider ${created.name} added")
        return created
    }

    @MutationMapping
    fun updateModelProvider(@Argument id: Long, @Argument input: UpdateProviderInput): ModelProviderView {
        val provider = models.provider(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw ModelProviderNotFoundException(id)

        val updated = models.updateProvider(id, input)
        val message = if (provider.name == updated.name) {
            "Provider ${updated.name} updated"
        } else {
            "Provider ${provider.name} renamed to ${updated.name}"
        }
        auditRecorder.record(provider.workspaceId, WorkspaceAuditCategory.MODEL, message)
        return updated
    }

    @MutationMapping
    fun removeModelProvider(@Argument id: Long): Boolean {
        val provider = models.provider(id)?.takeIf { access.canSee(it.workspaceId) } ?: return false
        if (!models.removeProvider(id)) return false

        auditRecorder.record(
            provider.workspaceId,
            WorkspaceAuditCategory.MODEL,
            "Provider ${provider.name} removed, with its models",
        )
        return true
    }

    @MutationMapping
    fun revealModelProviderSecret(@Argument id: Long): String? {
        val provider = models.provider(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw ModelProviderNotFoundException(id)

        auditRecorder.record(
            provider.workspaceId,
            WorkspaceAuditCategory.MODEL,
            "Credentials for ${provider.name} revealed",
        )
        return models.revealProviderSecret(id)
    }

    /** The Test Connection button: what comes back is what the provider said. */
    @MutationMapping
    fun testModelProvider(@Argument id: Long): ModelProviderView {
        val provider = models.provider(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw ModelProviderNotFoundException(id)

        val checked = models.testProvider(id)
        auditRecorder.record(
            provider.workspaceId,
            WorkspaceAuditCategory.MODEL,
            "Provider ${provider.name} checked: ${checked.lastCheckMessage}",
        )
        return checked
    }

    @MutationMapping
    fun createModel(@Argument input: CreateModelInput): LlmModelView {
        val provider = models.provider(input.providerId)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw ModelProviderNotFoundException(input.providerId)

        val created = models.createModel(input)
        auditRecorder.record(
            provider.workspaceId,
            WorkspaceAuditCategory.MODEL,
            "Model ${created.name} added to ${provider.name}",
        )
        return created
    }

    /** A copy of a model beside it, under "(copy)"; see [ModelService.duplicateModel]. */
    @MutationMapping
    fun duplicateModel(@Argument id: Long): LlmModelView {
        val model = models.model(id)?.takeIf { access.canSee(it.workspaceId) } ?: throw ModelNotFoundException(id)
        val copy = models.duplicateModel(id)
        auditRecorder.record(model.workspaceId, WorkspaceAuditCategory.MODEL, "Model ${model.name} duplicated as ${copy.name}")
        return copy
    }

    @MutationMapping
    fun updateModel(@Argument id: Long, @Argument input: UpdateModelInput): LlmModelView {
        val model = models.model(id)?.takeIf { access.canSee(it.workspaceId) } ?: throw ModelNotFoundException(id)

        val updated = models.updateModel(id, input)
        val message = if (model.name == updated.name) {
            "Model ${updated.name} updated"
        } else {
            "Model ${model.name} renamed to ${updated.name}"
        }
        auditRecorder.record(model.workspaceId, WorkspaceAuditCategory.MODEL, message)
        return updated
    }

    /**
     * Saves the Quotas and Limits card, whose fields go together: an emptied box
     * is a limit the workspace took off, not one it forgot to mention.
     */
    @MutationMapping
    fun updateModelQuotas(@Argument id: Long, @Argument input: ModelQuotasArgs): LlmModelView {
        val model = models.model(id)?.takeIf { access.canSee(it.workspaceId) } ?: throw ModelNotFoundException(id)

        val updated = models.updateModelQuotas(
            id,
            ModelQuotasInput(
                // Float on the wire, because a token limit outgrows a 32-bit Int.
                tokenLimit = input.tokenLimit?.toLong(),
                resetInterval = input.resetInterval ?: ResetInterval.MONTHLY,
                requestsPerMinute = input.requestsPerMinute,
            ),
        )
        auditRecorder.record(model.workspaceId, WorkspaceAuditCategory.MODEL, "Quotas for ${model.name} updated")
        return updated
    }

    @MutationMapping
    fun updateModelThrottle(@Argument id: Long, @Argument input: ModelThrottleArgs): LlmModelView {
        val model = models.model(id)?.takeIf { access.canSee(it.workspaceId) } ?: throw ModelNotFoundException(id)

        val updated = models.updateModelThrottle(
            id,
            ModelThrottleInput(
                // Float on the wire, because a token rate outgrows a 32-bit Int.
                throttleTokensPerSecond = input.throttleTokensPerSecond?.toLong(),
                throttleRequestsPerSecond = input.throttleRequestsPerSecond,
                acceptRetryAfter = input.acceptRetryAfter,
            ),
        )
        auditRecorder.record(model.workspaceId, WorkspaceAuditCategory.MODEL, "Throttle for ${model.name} updated")
        return updated
    }

    @MutationMapping
    fun setModelEnabled(@Argument id: Long, @Argument enabled: Boolean): LlmModelView {
        val model = models.model(id)?.takeIf { access.canSee(it.workspaceId) } ?: throw ModelNotFoundException(id)

        val updated = models.setModelEnabled(id, enabled)
        val what = if (enabled) "activated" else "deactivated"
        auditRecorder.record(model.workspaceId, WorkspaceAuditCategory.MODEL, "Model ${model.name} $what")
        return updated
    }

    @MutationMapping
    fun removeModel(@Argument id: Long): Boolean {
        val model = models.model(id)?.takeIf { access.canSee(it.workspaceId) } ?: return false
        if (!models.removeModel(id)) return false

        auditRecorder.record(model.workspaceId, WorkspaceAuditCategory.MODEL, "Model ${model.name} removed")
        return true
    }

    private fun requireWorkspaceAccess(workspaceId: Long) {
        access.requireVisible(workspaceId)
    }
}

/** The quotas card's values; the token limit arrives as a Float for its range. */
data class ModelQuotasArgs(
    val tokenLimit: Double? = null,
    val resetInterval: ResetInterval? = null,
    val requestsPerMinute: Int? = null,
)

/** The throttle card's values; the token rate arrives as a Float for its range. Issue #426. */
data class ModelThrottleArgs(
    val throttleTokensPerSecond: Double? = null,
    val throttleRequestsPerSecond: Double? = null,
    val acceptRetryAfter: Boolean? = null,
)

/** [ModelUsageView] with its dates as the ISO-8601 strings the schema says. */
data class ModelUsageResponse(
    val modelId: Long,
    val days: Int,
    val from: String,
    val to: String,
    val empty: Boolean,
    val requests: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val totalTokens: Long,
    val averageLatencyMillis: Double,
    val costEstimate: Double?,
    val requestsChange: Double?,
    val tokensChange: Double?,
    val latencyChange: Double?,
    val series: List<ModelUsageDayResponse>,
    val periodStart: String,
    val periodTokens: Long,
) {
    constructor(usage: ModelUsageView) : this(
        modelId = usage.modelId,
        days = usage.days,
        from = usage.from.format(DateTimeFormatter.ISO_LOCAL_DATE),
        to = usage.to.format(DateTimeFormatter.ISO_LOCAL_DATE),
        empty = usage.empty,
        requests = usage.requests,
        inputTokens = usage.inputTokens,
        outputTokens = usage.outputTokens,
        totalTokens = usage.totalTokens,
        averageLatencyMillis = usage.averageLatencyMillis,
        costEstimate = usage.costEstimate?.toDouble(),
        requestsChange = usage.requestsChange,
        tokensChange = usage.tokensChange,
        latencyChange = usage.latencyChange,
        series = usage.series.map {
            ModelUsageDayResponse(it.day.format(DateTimeFormatter.ISO_LOCAL_DATE), it.requests, it.tokens)
        },
        periodStart = usage.periodStart.format(DateTimeFormatter.ISO_LOCAL_DATE),
        periodTokens = usage.periodTokens,
    )
}

data class ModelUsageDayResponse(val day: String, val requests: Int, val tokens: Long)

class ModelProviderNotFoundException(val id: Long) : RuntimeException("No model provider with id $id"), Refusal {

    override val arguments get() = mapOf("id" to id)
}

class ModelNotFoundException(val id: Long) : RuntimeException("No model with id $id"), Refusal {

    override val arguments get() = mapOf("id" to id)
}


/**
 * One end of a usage range that is not a date.
 *
 * Refused rather than ignored: a window that quietly fell back to the last
 * thirty days would be a chart answering a different question from the one on
 * screen, which is worse than a sentence saying the date was not read.
 */
class UsageRangeInvalidException(val which: String, val said: String) : RuntimeException(
    "\"$said\" is not a date. Give `$which` as YYYY-MM-DD, or leave it out.",
), Refusal {

    override val arguments get() = mapOf("which" to which, "said" to said)
}
