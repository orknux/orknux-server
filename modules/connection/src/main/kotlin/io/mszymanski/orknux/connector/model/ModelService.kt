package io.mszymanski.orknux.connector.model

import io.mszymanski.orknux.connector.CredentialReader
import io.mszymanski.orknux.connector.connection.CheckOutcome
import io.mszymanski.orknux.connector.security.HeldSecret
import io.mszymanski.orknux.connector.security.SecretCipher
import io.mszymanski.orknux.connector.security.SecretReferences
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * The LLM providers a workspace has, the models it reaches through them, and what
 * those models have been used for.
 *
 * The metrics are a sum over `model_usage_day`, which nothing writes to yet:
 * there is no runtime that calls a model. A workspace that has not used one is told
 * so rather than shown a number, which is the same rule an action with no
 * runtime follows.
 */
@Service
class ModelService(
    private val providers: ModelProviderRepository,
    private val models: LlmModelRepository,
    private val usage: ModelUsageRepository,
    private val probe: ModelProviderProbe,
    private val events: ApplicationEventPublisher,
    /** Only to recognise a credential that never came out of its envelope. */
    private val cipher: SecretCipher,
    /**
     * The rule about a field that may keep its own value or read a workspace
     * one. Shared, because it is every secret field in the product and not this
     * one - see [SecretReferences].
     */
    private val references: SecretReferences,
    /** Defaulted rather than a bean, the way `ConditionEvaluator` takes one. */
    private val clock: Clock = Clock.systemDefaultZone(),
) {

    fun providers(workspaceId: Long): List<ModelProviderView> =
        providers.findByWorkspaceId(workspaceId, Sort.by("name")).map(::view)

    fun provider(id: Long): ModelProviderView? =
        providers.findByIdOrNull(id)?.let(::view)

    /**
     * The providers in this workspace reading [variableId].
     *
     * What `VariableAPI` asks before it removes a variable or takes its secrecy
     * away. A [CredentialReader] rather than the row: the answer is read by
     * somebody, and a provider row is a credential holder this has no business
     * handing out. The id travels with the name so that whoever is told about it
     * can open it.
     */
    fun providersReading(workspaceId: Long, variableId: Long): List<CredentialReader> =
        providers.findByWorkspaceIdAndSecretVariableId(workspaceId, variableId)
            .map { CredentialReader(requireNotNull(it.id), it.name) }
            .sortedBy { it.name }

    /** Every model the workspace may reach, provider first and then name. */
    fun models(workspaceId: Long): List<LlmModelView> {
        val byId = providers.findByWorkspaceId(workspaceId, Sort.by("name")).associateBy { requireNotNull(it.id) }
        if (byId.isEmpty()) return emptyList()

        return models.findByProviderIdIn(byId.keys, Sort.by("name"))
            .map { LlmModelView(it, requireNotNull(byId[it.providerId])) }
            .sortedWith(compareBy({ it.providerName }, { it.name }))
    }

    fun model(id: Long): LlmModelView? {
        val model = models.findByIdOrNull(id) ?: return null
        val provider = providers.findByIdOrNull(model.providerId) ?: return null
        return LlmModelView(model, provider)
    }

    @Transactional
    fun createProvider(input: CreateProviderInput): ModelProviderView {
        val name = input.name.trim()
        val endpoint = input.endpoint.trim()
        if (name.isEmpty()) throw ModelProviderNameInvalidException()
        if (endpoint.isEmpty()) throw ModelProviderEndpointInvalidException()
        if (providers.findByWorkspaceIdAndName(input.workspaceId, name) != null) {
            throw ModelProviderNameTakenException(name)
        }

        val own = input.secret?.trim()?.ifEmpty { null }
        val reference = references.bind(input.workspaceId, input.secretVariableId, own)

        val provider = ModelProvider(
            workspaceId = input.workspaceId,
            name = name,
            type = input.type ?: ProviderType.OPENAI,
            endpoint = endpoint,
            authMethod = input.authMethod ?: ProviderAuthMethod.API_KEY,
            secret = if (reference == null) own else null,
            secretVariableId = reference,
            apiVersion = input.apiVersion?.trim()?.ifEmpty { null },
            deploymentName = input.deploymentName?.trim()?.ifEmpty { null },
            region = input.region?.trim()?.ifEmpty { null },
            tenantId = input.tenantId?.trim()?.ifEmpty { null },
            clientId = input.clientId?.trim()?.ifEmpty { null },
            scope = input.scope?.trim()?.ifEmpty { null },
            checkEnabled = input.checkEnabled ?: true,
        )
        provider.forgetCheck()
        val saved = providers.save(provider)
        // Checked as soon as the transaction lands, so a provider that was just
        // given a key does not sit on "Not checked" until the next sweep.
        events.publishEvent(ModelProviderSaved(requireNotNull(saved.id)))
        return view(saved)
    }

    /**
     * Backs the provider form; a null secret keeps the stored one, empty clears it.
     *
     * The credential is a choice of two and the choice is made by what arrives.
     * A key given is a provider keeping its own copy, so any reference it held is
     * dropped; a variable given is a provider reading one, so any copy it held is
     * dropped with it. An empty key clears the credential whichever kind it was,
     * which is the only way back to a provider with nothing configured. Both at
     * once is refused rather than resolved by precedence: the caller has not
     * chosen, and guessing for them is how a key ends up somewhere nobody
     * intended.
     *
     * Anything saved here can change what a check would find, so the last one is
     * forgotten rather than left to describe a provider that has moved.
     */
    @Transactional
    fun updateProvider(id: Long, input: UpdateProviderInput): ModelProviderView {
        val provider = providers.findByIdOrNull(id) ?: throw ModelProviderNotFoundException(id)

        val name = input.name.trim()
        val endpoint = input.endpoint.trim()
        if (name.isEmpty()) throw ModelProviderNameInvalidException()
        if (endpoint.isEmpty()) throw ModelProviderEndpointInvalidException()
        if (name != provider.name && providers.findByWorkspaceIdAndName(provider.workspaceId, name) != null) {
            throw ModelProviderNameTakenException(name)
        }

        val own = input.secret?.trim()
        val reference = references.bind(provider.workspaceId, input.secretVariableId, own?.ifEmpty { null })

        provider.name = name
        provider.endpoint = endpoint
        input.type?.let { provider.type = it }
        input.authMethod?.let { provider.authMethod = it }
        when {
            reference != null -> {
                provider.secretVariableId = reference
                provider.secret = null
            }

            own != null -> {
                provider.secret = own.ifEmpty { null }
                provider.secretVariableId = null
            }
        }
        provider.apiVersion = input.apiVersion?.trim()?.ifEmpty { null }
        provider.deploymentName = input.deploymentName?.trim()?.ifEmpty { null }
        provider.region = input.region?.trim()?.ifEmpty { null }
        provider.tenantId = input.tenantId?.trim()?.ifEmpty { null }
        provider.clientId = input.clientId?.trim()?.ifEmpty { null }
        provider.scope = input.scope?.trim()?.ifEmpty { null }
        // Null leaves it alone, unlike the fields above: those are the form's
        // own and it sends all of them, while a caller written before this
        // column existed would otherwise turn checking back on every save.
        input.checkEnabled?.let { provider.checkEnabled = it }
        // The provider form sends both rates, so they are set as given and a
        // cleared field is no default throttle. Retry-After is a checkbox that
        // is always sent, but left as it is when it is not. Issue #426.
        provider.throttleTokensPerSecond = input.throttleTokensPerSecond?.toLong()
        provider.throttleRequestsPerSecond = input.throttleRequestsPerSecond
        input.acceptRetryAfter?.let { provider.acceptRetryAfter = it }

        provider.forgetCheck()
        events.publishEvent(ModelProviderSaved(id))
        return view(provider)
    }

    /**
     * A provider as a screen sees it, with the variable it reads named.
     *
     * The name is read here rather than left to the caller so that a broken
     * reference has somewhere to be reported from. There is one lookup per
     * provider and a workspace has a handful of them, which is not a query
     * budget worth complicating this for.
     */
    private fun view(provider: ModelProvider): ModelProviderView {
        val held = references.describe(provider.workspaceId, provider.secretVariableId)
        return ModelProviderView(provider, held)
    }

    /**
     * Asks the provider whether it is there, and records what it said.
     *
     * This is the Test Connection button. What comes back is the provider's own
     * answer — a refused credential is a failure, anything else that replies is
     * a provider that can be reached.
     */
    @Transactional
    fun testProvider(id: Long): ModelProviderView {
        val provider = providers.findByIdOrNull(id) ?: throw ModelProviderNotFoundException(id)

        val result = probe.check(provider)
        provider.status = when (result.outcome) {
            CheckOutcome.CONNECTED -> ProviderStatus.CONNECTED
            CheckOutcome.FAILED -> ProviderStatus.FAILED
        }
        provider.lastCheckMessage = result.message.take(MESSAGE_LENGTH)
        provider.lastCheckedAt = OffsetDateTime.now(clock)
        return view(provider)
    }

    /**
     * What the provider offers, against what the workspace has already added.
     *
     * A suggestion, not an import. Half of a model row is workspace policy —
     * what people call it, its quotas, whether it is on — and none of that is
     * discoverable. What is discoverable is the id, which is the part nobody
     * should have to transcribe from a provider's documentation.
     *
     * Nothing is written here, so this is a query: asking what is on offer
     * changes nothing until somebody adds one.
     */
    fun discoverModels(providerId: Long): List<DiscoveredModelView> {
        val provider = providers.findByIdOrNull(providerId) ?: throw ModelProviderNotFoundException(providerId)

        val offered = when (val listing = probe.list(provider)) {
            is ModelProviderProbe.Listing.Failed -> throw ModelDiscoveryFailedException(provider.name, listing.reason)
            is ModelProviderProbe.Listing.Models -> listing.ids
        }

        val added = models.findByProviderIdIn(setOf(providerId), Sort.by("name")).map { it.modelId }.toSet()
        return offered.sorted().map { DiscoveredModelView(modelId = it, added = it in added) }
    }

    /** Takes the models with it, which is what the cascade is for. */
    @Transactional
    fun removeProvider(id: Long): Boolean {
        val provider = providers.findByIdOrNull(id) ?: return false
        providers.delete(provider)
        return true
    }

    @Transactional
    fun createModel(input: CreateModelInput): LlmModelView {
        val provider = providers.findByIdOrNull(input.providerId)
            ?: throw ModelProviderNotFoundException(input.providerId)

        val name = input.name.trim()
        val modelId = input.modelId.trim()
        if (name.isEmpty()) throw ModelNameInvalidException()
        if (modelId.isEmpty()) throw ModelIdInvalidException()
        if (models.findByProviderIdAndName(input.providerId, name) != null) {
            throw ModelNameTakenException(name)
        }

        val model = models.save(
            LlmModel(
                providerId = input.providerId,
                name = name,
                modelId = modelId,
                kind = input.kind ?: ModelKind.CHAT,
                contextWindow = input.contextWindow,
                maxOutput = input.maxOutput,
                parallelToolCalls = input.parallelToolCalls,
                temperature = sampled(input.temperature, "temperature", 0.0, 2.0),
                topP = sampled(input.topP, "top-p", 0.0, 1.0),
                topK = input.topK?.also { require(it in 0..1000) { "Top-k has to be between 0 and 1000." } },
                minP = sampled(input.minP, "min-p", 0.0, 1.0),
                repeatPenalty = sampled(input.repeatPenalty, "repeat penalty", 0.0, 2.0),
                tokenLimit = input.tokenLimit,
                resetInterval = input.resetInterval ?: ResetInterval.MONTHLY,
                requestsPerMinute = input.requestsPerMinute,
                inputCostPerMillion = input.inputCostPerMillion?.toBigDecimal(),
                outputCostPerMillion = input.outputCostPerMillion?.toBigDecimal(),
                voice = input.voice?.trim()?.ifEmpty { null },
                skipEmptyLines = input.skipEmptyLines ?: false,
                imageCostPerImage = input.imageCostPerImage?.toBigDecimal(),
            ),
        )
        return LlmModelView(model, provider)
    }

    /**
     * Backs the model's own details: what it is called, what the API is given,
     * and what it costs. The form sends all of them, so a null is a cleared
     * field rather than one nobody mentioned.
     */
    @Transactional
    fun updateModel(id: Long, input: UpdateModelInput): LlmModelView {
        val model = models.findByIdOrNull(id) ?: throw ModelNotFoundException(id)
        val provider = providers.findByIdOrNull(model.providerId)
            ?: throw ModelProviderNotFoundException(model.providerId)

        val name = input.name.trim()
        val modelId = input.modelId.trim()
        if (name.isEmpty()) throw ModelNameInvalidException()
        if (modelId.isEmpty()) throw ModelIdInvalidException()
        if (name != model.name && models.findByProviderIdAndName(model.providerId, name) != null) {
            throw ModelNameTakenException(name)
        }

        model.name = name
        model.modelId = modelId
        model.kind = input.kind ?: model.kind
        model.contextWindow = input.contextWindow
        model.maxOutput = input.maxOutput
        model.parallelToolCalls = input.parallelToolCalls
        model.temperature = sampled(input.temperature, "temperature", 0.0, 2.0)
        model.topP = sampled(input.topP, "top-p", 0.0, 1.0)
        model.topK = input.topK?.also { require(it in 0..1000) { "Top-k has to be between 0 and 1000." } }
        model.minP = sampled(input.minP, "min-p", 0.0, 1.0)
        model.repeatPenalty = sampled(input.repeatPenalty, "repeat penalty", 0.0, 2.0)
        model.inputCostPerMillion = input.inputCostPerMillion?.toBigDecimal()
        model.outputCostPerMillion = input.outputCostPerMillion?.toBigDecimal()
        model.voice = input.voice?.trim()?.ifEmpty { null }
        // Absent means off, as it does on the form: the box is either ticked or
        // it is not, and there is no third thing for a null to mean.
        model.skipEmptyLines = input.skipEmptyLines ?: false
        model.imageCostPerImage = input.imageCostPerImage?.toBigDecimal()
        return LlmModelView(model, provider)
    }

    /**
     * Backs the Quotas and Limits card, which saves its three fields together.
     *
     * A null is no limit — an emptied box on that form means the workspace took the
     * limit off, which is a thing it has to be able to do.
     */
    @Transactional
    fun updateModelQuotas(id: Long, input: ModelQuotasInput): LlmModelView {
        val model = models.findByIdOrNull(id) ?: throw ModelNotFoundException(id)
        val provider = providers.findByIdOrNull(model.providerId)
            ?: throw ModelProviderNotFoundException(model.providerId)

        model.tokenLimit = input.tokenLimit
        model.resetInterval = input.resetInterval
        model.requestsPerMinute = input.requestsPerMinute
        return LlmModelView(model, provider)
    }

    /**
     * Sets a model's rate throttle, overriding its provider's default. Issue #426.
     *
     * The card sends all three, so they are set as given: a null rate inherits
     * the provider, a 0 turns it off, and a null Retry-After inherits too. All
     * are valid stored values, which is why this is a plain set and not the
     * null-leaves-alone the provider fields use.
     */
    @Transactional
    fun updateModelThrottle(id: Long, input: ModelThrottleInput): LlmModelView {
        val model = models.findByIdOrNull(id) ?: throw ModelNotFoundException(id)
        val provider = providers.findByIdOrNull(model.providerId)
            ?: throw ModelProviderNotFoundException(model.providerId)

        model.throttleTokensPerSecond = input.throttleTokensPerSecond
        model.throttleRequestsPerSecond = input.throttleRequestsPerSecond
        model.acceptRetryAfter = input.acceptRetryAfter
        return LlmModelView(model, provider)
    }

    @Transactional
    fun setModelEnabled(id: Long, enabled: Boolean): LlmModelView {
        val model = models.findByIdOrNull(id) ?: throw ModelNotFoundException(id)
        val provider = providers.findByIdOrNull(model.providerId)
            ?: throw ModelProviderNotFoundException(model.providerId)

        model.enabled = enabled
        return LlmModelView(model, provider)
    }

    @Transactional
    fun removeModel(id: Long): Boolean {
        val model = models.findByIdOrNull(id) ?: return false
        models.delete(model)
        return true
    }

    /**
     * Hands the stored key back, for the provider form's "Reveal" action.
     *
     * One that could not be decrypted comes back as null rather than as its
     * envelope: `orkx1:…` in a reveal box looks like the credential somebody
     * saved, and copying it somewhere would be copying nothing usable.
     *
     * A provider reading a variable reveals nothing here, and that is not an
     * omission. Revealing a secret is a deliberate act recorded against the
     * secret - `revealVariable` writes "Variable X revealed" into the audit log -
     * and a second door onto the same value through the provider would be the
     * same value read with the wrong thing's name on the record.
     */
    @Transactional
    fun revealProviderSecret(id: Long): String? {
        val provider = providers.findByIdOrNull(id) ?: throw ModelProviderNotFoundException(id)
        if (provider.secretVariableId != null) return null
        log.info("Credentials for model provider {} (workspace {}) revealed", provider.name, provider.workspaceId)
        return provider.secret?.takeUnless { cipher.isEncrypted(it) }
    }

    /**
     * What one model has been used for over the last [days], and how that
     * compares with the [days] before it.
     *
     * Everything here is a sum over the recorded days, so a model nothing has
     * called reports zero and says the window is empty, rather than showing a
     * shape with no numbers behind it.
     */
    /**
     * What a model was used for, over a window and against the one before it.
     *
     * @param days the window as a count back from today, which is what the page
     *   opens on and what every caller asked for until there were dates.
     * @param from the first day, where the window is a range somebody chose.
     *   With [to] this replaces [days] entirely: every question anybody brings
     *   to this page is about a window that is not the last thirty days - what
     *   yesterday's run cost, what was spent last month, whether the spike on
     *   the 14th was this model - and none of them could be asked.
     * @param to the last day. Defaults to today where only [from] was given,
     *   because "since the 1st" is a range and asking somebody to type today's
     *   date to say "until now" is asking them to type what the server knows.
     */
    fun usage(
        modelId: Long,
        days: Int = USAGE_DAYS,
        from: LocalDate? = null,
        to: LocalDate? = null,
    ): ModelUsageView {
        val model = models.findByIdOrNull(modelId) ?: throw ModelNotFoundException(modelId)

        val today = LocalDate.now(clock)

        /*
         * The window, from whichever of the two was asked for.
         *
         * A range the wrong way round is read rather than refused: somebody who
         * picked the two dates in the other order meant the days between them,
         * and a chart that answers is better than an error about which box was
         * which. A range running past today is cut off at today - there is no
         * usage in the future, and drawing empty days after the last one would
         * squash the part somebody is looking at.
         */
        val since: LocalDate
        val until: LocalDate
        when {
            from != null && to != null -> {
                // Either way round: somebody who picked the two dates in the
                // other order meant the days between them, and an error about
                // which box was which answers nothing they asked.
                until = minOf(maxOf(from, to), today)
                since = minOf(minOf(from, to), until)
            }
            // One end and nothing else is a half-open range, read the way it is
            // said: "since the 1st" runs to now, and "up to the 14th" is the
            // window ending there - asking somebody to type today's date to mean
            // "until now" is asking them to type what the server already knows.
            from != null -> {
                until = today
                since = minOf(from, until)
            }
            to != null -> {
                until = minOf(to, today)
                since = until.minusDays((days - 1).toLong())
            }
            else -> {
                until = today
                since = until.minusDays((days - 1).toLong())
            }
        }
        val span = ChronoUnit.DAYS.between(since, until).toInt() + 1
        val previousFrom = since.minusDays(span.toLong())

        val current = usage.findByModelIdAndDayBetweenOrderByDayAsc(modelId, since, until)
        val previous = usage.findByModelIdAndDayBetweenOrderByDayAsc(modelId, previousFrom, since.minusDays(1))

        val totals = Totals(current)
        val before = Totals(previous)

        // The quota runs on its own clock, which is rarely the reporting window.
        val periodStart = periodStart(model.resetInterval, today)
        val inPeriod = Totals(usage.findByModelIdAndDayBetweenOrderByDayAsc(modelId, periodStart, today))

        return ModelUsageView(
            modelId = modelId,
            // What the window actually came to, which is the number the headings
            // say. A range of three days that was asked for as a range is three
            // days here, not the thirty nobody asked for.
            days = span,
            from = since,
            to = until,
            requests = totals.requests,
            inputTokens = totals.inputTokens,
            outputTokens = totals.outputTokens,
            totalTokens = totals.tokens,
            averageLatencyMillis = totals.averageLatencyMillis,
            costEstimate = cost(model, totals),
            requestsChange = change(totals.requests.toDouble(), before.requests.toDouble()),
            tokensChange = change(totals.tokens.toDouble(), before.tokens.toDouble()),
            latencyChange = change(totals.averageLatencyMillis, before.averageLatencyMillis),
            // Every day in the window, including the ones nothing happened on.
            // Only the days with a row used to be sent, so a model used once was
            // a series of one point — and a line through one point draws nothing,
            // which is why a real request looked like no chart at all.
            series = daily(since, until, current),
            periodStart = periodStart,
            periodTokens = inPeriod.tokens,
        )
    }

    /** One entry per day between the two, zero where nothing was recorded. */
    private fun daily(from: LocalDate, to: LocalDate, recorded: List<ModelUsageDay>): List<ModelUsageDayView> {
        val byDay = recorded.associateBy { it.day }
        return generateSequence(from) { it.plusDays(1) }
            .takeWhile { !it.isAfter(to) }
            .map { day ->
                val held = byDay[day]
                ModelUsageDayView(
                    day = day,
                    requests = held?.requests ?: 0,
                    tokens = held?.let { it.inputTokens + it.outputTokens } ?: 0,
                )
            }
            .toList()
    }

    /**
     * When the current quota period began.
     *
     * A quota that never resets counts from the first day anything was recorded,
     * which for these purposes is far enough back to be "everything".
     */
    private fun periodStart(interval: ResetInterval, today: LocalDate): LocalDate = when (interval) {
        ResetInterval.DAILY -> today
        ResetInterval.WEEKLY -> today.minusDays((today.dayOfWeek.value - 1).toLong())
        ResetInterval.MONTHLY -> today.withDayOfMonth(1)
        ResetInterval.NEVER -> today.minus(FOREVER_YEARS, ChronoUnit.YEARS)
    }

    /**
     * What the window cost, at what the provider is recorded as charging. Null
     * when the model carries no prices, because a guess would be worse.
     *
     * An image model is costed on its requests rather than on its tokens, and
     * that is not a refinement — it is the difference between a number and a
     * lie. These models are billed per picture and report no tokens at all, so
     * the token arithmetic returns `0.00` for a month that was paid for. One
     * request is one picture because [ModelImageClient] asks for exactly one.
     */
    private fun cost(model: LlmModel, totals: Totals): BigDecimal? =
        if (model.kind == ModelKind.IMAGE) {
            ModelPricing.imageCost(model, totals.requests.toLong())
        } else {
            ModelPricing.cost(model, totals.inputTokens, totals.outputTokens, ModelPricing.WINDOW_SCALE)
        }

    /**
     * What one call cost, for a caller holding the counts a model reported.
     *
     * The same arithmetic the window above is reported with, at the finer scale
     * a single answer needs - see [ModelPricing]. Null for a model that has been
     * removed as well as for one carrying no prices: both mean there is nothing
     * to cost it at, and a caller that shows nothing either way does not need to
     * tell them apart.
     */
    fun costOf(modelId: Long, inputTokens: Long, outputTokens: Long): BigDecimal? {
        val model = models.findByIdOrNull(modelId) ?: return null
        return ModelPricing.cost(model, inputTokens, outputTokens)
    }

    /**
     * What a number of pictures cost, for the chat that has just drawn one.
     *
     * The twin of [costOf], and separate from it because the question is a
     * different one: that asks what tokens cost, and an image model has none.
     * Null for a removed model as well as for one carrying no per-image price,
     * since a caller that shows nothing either way need not tell them apart.
     */
    fun imageCostOf(modelId: Long, images: Long): BigDecimal? {
        val model = models.findByIdOrNull(modelId) ?: return null
        return ModelPricing.imageCost(model, images)
    }

    /** The change from one window to the next, as a fraction. Null when there was nothing to compare with. */
    private fun change(now: Double, before: Double): Double? {
        if (before == 0.0) return null
        return (now - before) / before
    }

    /** One window's worth of days, added up. */
    private class Totals(days: List<ModelUsageDay>) {
        val requests: Int = days.sumOf { it.requests }
        val inputTokens: Long = days.sumOf { it.inputTokens }
        val outputTokens: Long = days.sumOf { it.outputTokens }
        val tokens: Long = inputTokens + outputTokens
        val averageLatencyMillis: Double =
            if (requests == 0) 0.0 else days.sumOf { it.latencyMillisTotal }.toDouble() / requests
    }

    private companion object {
        val log = LoggerFactory.getLogger(ModelService::class.java)

        /** The window the metrics card reports on. */
        const val USAGE_DAYS = 30

        /** Matches the column. */
        const val MESSAGE_LENGTH = 500
        const val FOREVER_YEARS = 100L
    }
}

data class CreateProviderInput(
    val workspaceId: Long,
    val name: String,
    val endpoint: String,
    val type: ProviderType? = null,
    val authMethod: ProviderAuthMethod? = null,
    /** The API key, or the Entra client secret; not with [secretVariableId]. */
    val secret: String? = null,
    /** A workspace secret to read the credential from instead of keeping a copy. */
    val secretVariableId: Long? = null,
    val apiVersion: String? = null,
    val deploymentName: String? = null,
    val region: String? = null,
    val tenantId: String? = null,
    val clientId: String? = null,
    val scope: String? = null,
    /**
     * Whether the sweep may call this provider. Null on create is on, which is
     * what a provider somebody has just configured wants.
     */
    val checkEnabled: Boolean? = null,
)

data class UpdateProviderInput(
    val name: String,
    val endpoint: String,
    val type: ProviderType? = null,
    val authMethod: ProviderAuthMethod? = null,
    /**
     * Null leaves the stored credential alone; empty clears it, reference and
     * all. A value stores a copy here and drops any reference.
     */
    val secret: String? = null,
    /**
     * Points the provider at a workspace secret, dropping any copy it held.
     * Null leaves the credential as it is; sending it with [secret] is refused.
     */
    val secretVariableId: Long? = null,
    val apiVersion: String? = null,
    val deploymentName: String? = null,
    val region: String? = null,
    val tenantId: String? = null,
    val clientId: String? = null,
    val scope: String? = null,
    /** Null leaves it as it is, so a caller that does not know about it cannot turn it off. */
    val checkEnabled: Boolean? = null,
    /**
     * The default throttle for this provider's models. The provider form sends
     * these with the rest, so they are set as given - a null clears the rate to
     * no default. Issue #426.
     */
    val throttleTokensPerSecond: Double? = null,
    val throttleRequestsPerSecond: Double? = null,
    /** Null leaves it as it is, like [checkEnabled]. Issue #426. */
    val acceptRetryAfter: Boolean? = null,
)

data class CreateModelInput(
    val providerId: Long,
    val name: String,
    val modelId: String,
    val kind: ModelKind? = null,
    val contextWindow: Int? = null,
    val maxOutput: Int? = null,
    /** Null lets the provider decide; false is one tool call per reply. Issue #530. */
    val parallelToolCalls: Boolean? = null,
    /** How the model picks its words; null sends nothing. Issue #533. */
    val temperature: Double? = null,
    val topP: Double? = null,
    val topK: Int? = null,
    val minP: Double? = null,
    val repeatPenalty: Double? = null,
    val tokenLimit: Long? = null,
    val resetInterval: ResetInterval? = null,
    val requestsPerMinute: Int? = null,
    val inputCostPerMillion: Double? = null,
    val outputCostPerMillion: Double? = null,
    /** Only meaningful for a SPEECH model; the names belong to the provider. */
    val voice: String? = null,
    /** Whether a SPEECH model is handed text with its empty lines taken out. */
    val skipEmptyLines: Boolean? = null,
    /** Only meaningful for an IMAGE model, which is billed per picture rather than per token. */
    val imageCostPerImage: Double? = null,
)

/** The model's own details, all of them, as the form that edits them sends them. */
data class UpdateModelInput(
    val name: String,
    val modelId: String,
    val kind: ModelKind? = null,
    val contextWindow: Int? = null,
    val maxOutput: Int? = null,
    /** Null lets the provider decide; false is one tool call per reply. Issue #530. */
    val parallelToolCalls: Boolean? = null,
    /** How the model picks its words; null sends nothing. Issue #533. */
    val temperature: Double? = null,
    val topP: Double? = null,
    val topK: Int? = null,
    val minP: Double? = null,
    val repeatPenalty: Double? = null,
    val inputCostPerMillion: Double? = null,
    val outputCostPerMillion: Double? = null,
    /** Only meaningful for a SPEECH model; the names belong to the provider. */
    val voice: String? = null,
    /** Whether a SPEECH model is handed text with its empty lines taken out. */
    val skipEmptyLines: Boolean? = null,
    /** Only meaningful for an IMAGE model, which is billed per picture rather than per token. */
    val imageCostPerImage: Double? = null,
)

/** The Quotas and Limits card, which saves its fields together. Null is no limit. */
data class ModelQuotasInput(
    val tokenLimit: Long? = null,
    val resetInterval: ResetInterval = ResetInterval.MONTHLY,
    val requestsPerMinute: Int? = null,
)

/**
 * The Throttle card on the model page, which saves its fields together. A null
 * rate inherits the provider's default; a 0 turns that dimension off; a null
 * acceptRetryAfter inherits the provider's choice. Issue #426.
 */
data class ModelThrottleInput(
    val throttleTokensPerSecond: Long? = null,
    val throttleRequestsPerSecond: Double? = null,
    val acceptRetryAfter: Boolean? = null,
)

data class ModelProviderView(
    val id: Long,
    val workspaceId: Long,
    val name: String,
    val type: ProviderType,
    val endpoint: String,
    val authMethod: ProviderAuthMethod,
    val apiVersion: String?,
    val deploymentName: String?,
    val region: String?,
    val tenantId: String?,
    val clientId: String?,
    val scope: String?,
    /**
     * Whether the sweep calls this provider. Off is a provider nobody wants
     * polled - a model server that is only sometimes running - and it is why
     * the status beside it may be "Not checked" indefinitely.
     */
    val checkEnabled: Boolean,
    /** The default throttle this provider's models inherit; null on a rate is none. Issue #426. */
    val throttleTokensPerSecond: Long?,
    val throttleRequestsPerSecond: Double?,
    /** Whether a 429's Retry-After is obeyed by default. Issue #426. */
    val acceptRetryAfter: Boolean,
    val status: ProviderStatus,
    val lastCheckMessage: String?,
    /** ISO-8601, as `WorkspaceConnectionView` reports its own. */
    val lastCheckedAt: String?,
    /** Whether the provider holds a copy of its own. False for one reading a variable. */
    val secretSet: Boolean,
    /** The workspace secret it reads instead, or null when it keeps its own copy. */
    val secretVariableId: Long?,
    /** What that variable is called, and which catalog holds it. */
    val secretVariableName: String?,
    val secretVariableCatalog: String?,
    /**
     * A reference pointing at nothing.
     *
     * Should not happen - a variable a provider reads cannot be deleted - but a
     * restore, a workspace removed out from under it or a hand-edited database
     * can each produce one, and a provider that cannot say why it has no key is
     * precisely the failure this design exists to avoid. So it is reported here
     * as well as in the check's own words.
     */
    val secretVariableMissing: Boolean,
) {
    constructor(provider: ModelProvider, held: HeldSecret? = null) : this(
        id = requireNotNull(provider.id),
        workspaceId = provider.workspaceId,
        name = provider.name,
        type = provider.type,
        endpoint = provider.endpoint,
        authMethod = provider.authMethod,
        apiVersion = provider.apiVersion,
        deploymentName = provider.deploymentName,
        region = provider.region,
        tenantId = provider.tenantId,
        clientId = provider.clientId,
        scope = provider.scope,
        checkEnabled = provider.checkEnabled,
        throttleTokensPerSecond = provider.throttleTokensPerSecond,
        throttleRequestsPerSecond = provider.throttleRequestsPerSecond,
        acceptRetryAfter = provider.acceptRetryAfter,
        status = provider.status,
        lastCheckMessage = provider.lastCheckMessage,
        lastCheckedAt = provider.lastCheckedAt?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        secretSet = !provider.secret.isNullOrBlank(),
        secretVariableId = provider.secretVariableId,
        secretVariableName = held?.name,
        secretVariableCatalog = held?.catalog,
        secretVariableMissing = provider.secretVariableId != null && held == null,
    )
}

data class LlmModelView(
    val id: Long,
    val providerId: Long,
    val workspaceId: Long,
    val providerName: String,
    val name: String,
    val modelId: String,
    val kind: ModelKind,
    val contextWindow: Int?,
    val maxOutput: Int?,
    val parallelToolCalls: Boolean?,
    val temperature: Double?,
    val topP: Double?,
    val topK: Int?,
    val minP: Double?,
    val repeatPenalty: Double?,
    val enabled: Boolean,
    val tokenLimit: Long?,
    val resetInterval: ResetInterval,
    val requestsPerMinute: Int?,
    /** This model's own throttle; null on a rate inherits the provider, 0 turns it off. Issue #426. */
    val throttleTokensPerSecond: Long?,
    val throttleRequestsPerSecond: Double?,
    /** Whether it obeys a 429's Retry-After; null inherits the provider. Issue #426. */
    val acceptRetryAfter: Boolean?,
    val inputCostPerMillion: Double?,
    val outputCostPerMillion: Double?,
    /** Which voice a SPEECH model reads in; null sends none. */
    val voice: String?,
    /** Whether a SPEECH model is handed text with its empty lines taken out. */
    val skipEmptyLines: Boolean,
    /** What one picture costs on an IMAGE model; null is not recorded, which is not free. */
    val imageCostPerImage: Double?,
) {
    constructor(model: LlmModel, provider: ModelProvider) : this(
        id = requireNotNull(model.id),
        providerId = model.providerId,
        workspaceId = provider.workspaceId,
        providerName = provider.name,
        name = model.name,
        modelId = model.modelId,
        kind = model.kind,
        contextWindow = model.contextWindow,
        maxOutput = model.maxOutput,
        parallelToolCalls = model.parallelToolCalls,
        temperature = model.temperature,
        topP = model.topP,
        topK = model.topK,
        minP = model.minP,
        repeatPenalty = model.repeatPenalty,
        enabled = model.enabled,
        tokenLimit = model.tokenLimit,
        resetInterval = model.resetInterval,
        requestsPerMinute = model.requestsPerMinute,
        throttleTokensPerSecond = model.throttleTokensPerSecond,
        throttleRequestsPerSecond = model.throttleRequestsPerSecond,
        acceptRetryAfter = model.acceptRetryAfter,
        inputCostPerMillion = model.inputCostPerMillion?.toDouble(),
        outputCostPerMillion = model.outputCostPerMillion?.toDouble(),
        voice = model.voice,
        skipEmptyLines = model.skipEmptyLines,
        imageCostPerImage = model.imageCostPerImage?.toDouble(),
    )
}

/**
 * A model the provider says it can run.
 *
 * [added] rather than filtering the ones already in the catalogue out: a picker
 * that silently drops them looks like the provider stopped offering them.
 */
data class DiscoveredModelView(
    val modelId: String,
    val added: Boolean,
)

/** One day of the chart. */
data class ModelUsageDayView(
    val day: LocalDate,
    val requests: Int,
    val tokens: Long,
)

data class ModelUsageView(
    val modelId: Long,
    val days: Int,
    val from: LocalDate,
    val to: LocalDate,
    val requests: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val totalTokens: Long,
    val averageLatencyMillis: Double,
    /** Null when the model carries no prices to work it out from. */
    val costEstimate: BigDecimal?,
    /** Fractions against the window before this one; null when there was none. */
    val requestsChange: Double?,
    val tokensChange: Double?,
    val latencyChange: Double?,
    /** Only the days something happened on, so an untouched model has none. */
    val series: List<ModelUsageDayView>,
    /** Where the quota is counting from, which follows the reset interval. */
    val periodStart: LocalDate,
    val periodTokens: Long,
) {
    /**
     * True when nothing has been recorded, which the screen says rather than
     * showing zeros as a result. Asked of the totals rather than the series:
     * the series has an entry per day whether or not anything happened on it.
     */
    val empty: Boolean get() = requests == 0 && totalTokens == 0L
}

/** A sampling value inside its range, or refused in words. Issue #533. */
internal fun sampled(value: Double?, called: String, lowest: Double, highest: Double): Double? =
    value?.also { require(it in lowest..highest) { "The $called has to be between $lowest and $highest." } }
