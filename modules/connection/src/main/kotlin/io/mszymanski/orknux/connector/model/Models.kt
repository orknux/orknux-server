package io.mszymanski.orknux.connector.model

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import io.mszymanski.orknux.connector.security.SECRET_COLUMN_LENGTH
import io.mszymanski.orknux.connector.security.SecretConverter
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.repository.JpaRepository
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

/** What a model is for, which is what decides whether an agent may use it. */
enum class ModelKind {
    CHAT,
    EMBEDDING,
    COMPLETION,

    /** Speech in, text out: what the microphone in a chat is handed to. */
    TRANSCRIPTION,

    /** Text in, speech out: what reads an answer aloud. */
    SPEECH,

    /**
     * Text in, a picture out: what the picture button in a chat draws with.
     *
     * The fourth kind beyond the ordinary one, and the odd one of the four in
     * one respect: it is not the chat completions API under another path. Image
     * generation is its own endpoint with its own body, which is why
     * [ModelImageClient] exists beside the chat, speech and transcription
     * clients rather than inside one of them, and why a provider type that has
     * no such endpoint is refused in a sentence rather than called and left to
     * answer 404.
     */
    IMAGE,

    /**
     * A state and typed questions in, calibrated probabilities out: any server
     * speaking the Jev format, hosted or on the installation's own hardware. Not a language model at
     * all - it writes nothing - which is why it lives only under a
     * [ProviderType.SYSTEM_ONE] provider and is asked by [DecisionModelClient]
     * and by nothing else. Issue #577.
     */
    DECISION,
}

/** How often a token quota starts again. */
enum class ResetInterval {
    DAILY,
    WEEKLY,
    MONTHLY,

    /** The quota is a total, not a rate. */
    NEVER,
}

/**
 * The services a provider can be. Each brings its own settings and its own way in.
 *
 * A type is here because something branches on it. OPENAI is the shape the rest
 * are measured against; ANTHROPIC has its own body, its own streaming events and
 * its own `/messages` path; AZURE_OPENAI puts the deployment and the API version
 * in the URL and can authenticate through Entra ID; OLLAMA serves the OpenAI
 * shape under `/v1` of an address of your own, which is what [ModelProvider.openAiBase]
 * is for. GOOGLE_AI was removed in V170 because it branched on nothing except
 * the name of its auth header - see the migration.
 *
 * CUSTOM went in V224 for a smaller reason and a worse one. The smaller reason
 * is that it branched on nothing whatsoever: the one place in the codebase that
 * ever named it put it in the same arm as OPENAI, and every other decision made
 * about a provider - the auth header, the URL, the request body, the streaming
 * events - let it fall through to the OpenAI default. The worse reason is what
 * its name told people. Every other value here answers "what does this endpoint
 * speak"; CUSTOM answered "we have not heard of this one", which reads as a
 * promise that whatever the server speaks will be handled, and was delivered as
 * an OpenAI request every single time. A provider whose wire format we cannot
 * name is not a provider we can call, so there was nothing behind the promise to
 * keep.
 *
 * OPENAI is therefore the honest home for anything OpenAI-shaped at an address
 * of its own - a local llama.cpp, an inference gateway, Google's
 * OpenAI-compatible surface - because that is what was already being sent to it.
 * The part of such a provider that is genuinely custom is its endpoint, and the
 * endpoint field carries that untouched.
 */
enum class ProviderType {
    OPENAI,
    ANTHROPIC,
    AZURE_OPENAI,
    OLLAMA,

    /**
     * An endpoint speaking TypeSafe's `POST /v1/systemone`: its hosted Jev with
     * a key, or a self-hosted Laya, whose `laya-serve` exposes the same API,
     * with or without one. It answers questions rather than writing, so the
     * only models under it are [ModelKind.DECISION] ones and nothing that
     * talks to a chat model is ever pointed at it. Issue #577.
     */
    SYSTEM_ONE,
    ;

    /**
     * The most tools this provider will accept on one request.
     *
     * Asked of the provider rather than written at the call site, because the
     * number is the provider's and they do not agree. OpenAI and Azure refuse
     * the whole request over 128 - "Invalid 'tools': array too long" - and an
     * agent granted more than that could not answer at all, with the provider's
     * own sentence reaching whoever asked it. Anthropic bounds a request by its
     * size rather than by a count, so the number here is a ceiling this product
     * keeps rather than one it is given: a model handed three hundred tools
     * chooses badly long before any provider objects.
     *
     * What happens above it is [io.mszymanski.orknux.connector.model.ToolSpec]s
     * being found rather than carried - see the app's tool search - so this is
     * the number that decides when that starts, not a number anything fails on.
     */
    val toolLimit: Int
        get() = when (this) {
            OPENAI, AZURE_OPENAI -> 128
            OLLAMA -> 128
            ANTHROPIC -> 256
            // Takes no tools at all; nothing offers it any.
            SYSTEM_ONE -> 0
        }
}

/** How a provider is authenticated. */
enum class ProviderAuthMethod {
    /** A key sent on every request, in whichever header the type wants it. */
    API_KEY,

    /**
     * Microsoft Entra ID: a token fetched with a tenant, a client and a secret,
     * for the scope the resource asks for. Azure OpenAI only.
     */
    ENTRA_ID,
}

/**
 * What the Models screen says about a provider.
 *
 * CONNECTED only once a check reached the provider and it answered, which is
 * the same rule a workspace connection follows: a stored credential is not a working
 * one, and saying otherwise would be a guess dressed up as a fact.
 */
enum class ProviderStatus {
    /** Nothing to check with yet. */
    NOT_CONFIGURED,

    /** Configured, but no check has reached it. */
    NOT_CHECKED,
    CONNECTED,
    FAILED,
}

/**
 * An LLM provider a workspace reaches models through.
 *
 * It holds a key, which is why it lives in this module: credentials are read in
 * one place. Every provider authenticates the same way, so there is no auth
 * type to choose — a bearer key, or nothing configured yet.
 */
@Entity
@Table(name = "model_provider")
class ModelProvider(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    /** The display name, which is the workspace's to choose: "Azure OpenAI Production". */
    @Column(nullable = false, length = 120)
    var name: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    var type: ProviderType = ProviderType.OPENAI,

    @Column(nullable = false, length = 1000)
    var endpoint: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_method", nullable = false, length = 16)
    var authMethod: ProviderAuthMethod = ProviderAuthMethod.API_KEY,

    /**
     * The API key, or the Entra client secret: one column, one place to read.
     *
     * Encrypted in the database. The column is wider than the value it holds
     * because the envelope is base64 and carries an initialisation vector.
     *
     * Null when the credential is [secretVariableId]'s instead. The two are
     * exclusive, in the entity and in a CHECK constraint: a provider told to
     * read a variable and still holding an old copy of a key would be a
     * credential kept past the moment somebody decided to stop keeping it.
     */
    @Convert(converter = SecretConverter::class)
    @Column(length = SECRET_COLUMN_LENGTH)
    var secret: String? = null,

    /**
     * The workspace variable this provider reads its credential from, if it does.
     *
     * By id rather than by name, which is the opposite of how an agent's MCP
     * server grants work and deliberately so. A grant by name is what made #170
     * and #228 - a variable renamed, or moved to another catalog, would leave
     * the provider holding a name that matches nothing, and the loss would be
     * silent until the next call. An id survives both, so the only tidying
     * operation left to guard is the destructive one, and `VariableAPI` refuses
     * to delete a variable a provider is reading.
     *
     * Not a foreign key: `workspace_variable` is the application's table and this
     * is the connection module's, and module tables carry no keys across that
     * boundary. The guard is therefore in code, and the reference is still
     * reported as broken rather than assumed sound - a restore, a hand-edited
     * database or a workspace removed out from under it can all leave one
     * dangling, and a provider that cannot say why it has no key is the failure
     * this whole arrangement exists to avoid.
     */
    @Column(name = "secret_variable_id")
    var secretVariableId: Long? = null,

    @Column(name = "api_version", length = 32)
    var apiVersion: String? = null,

    @Column(name = "deployment_name", length = 120)
    var deploymentName: String? = null,

    @Column(length = 64)
    var region: String? = null,

    @Column(name = "tenant_id", length = 120)
    var tenantId: String? = null,

    @Column(name = "client_id", length = 120)
    var clientId: String? = null,

    @Column(length = 300)
    var scope: String? = null,

    /**
     * Whether the sweep is allowed to ask this provider anything.
     *
     * On for everything, which is what an installation wants for a provider it
     * pays for and relies on. Off is for the one somebody keeps configured
     * against a box that is not always running - a laptop's llama.cpp, a model
     * server started for an afternoon - where every sweep is a connection
     * refused, and the only thing it produces is a warning in the log for a
     * state nobody thinks is wrong.
     *
     * It stops the timer, not the button. Test Connection goes through
     * [ModelService.testProvider] whatever this says, because a check somebody
     * asked for is a check they want the answer to; what is turned off here is
     * asking on their behalf. The provider goes on being used for chats and
     * tasks either way - this decides who is *polled*, never who is called.
     */
    @Column(name = "check_enabled", nullable = false)
    var checkEnabled: Boolean = true,

    /**
     * The default rate this provider's models hold themselves to, so a run
     * stays under the provider's limit rather than being turned away with a
     * 429. The tokens and the requests each second, either or both; a model may
     * override them on its own page. Null is no default throttle. Issue #426.
     */
    @Column(name = "throttle_tokens_per_second")
    var throttleTokensPerSecond: Long? = null,

    @Column(name = "throttle_requests_per_second")
    var throttleRequestsPerSecond: Double? = null,

    /**
     * Whether a 429's Retry-After is obeyed: waiting exactly what it says, and
     * ahead of any node's own retry policy - retry-after wins. On by default;
     * turned off for a provider whose Retry-After is not to be trusted. A model
     * may override it. Issue #426.
     */
    @Column(name = "accept_retry_after", nullable = false)
    var acceptRetryAfter: Boolean = true,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: ProviderStatus = ProviderStatus.NOT_CONFIGURED,

    @Column(name = "last_check_message", length = 500)
    var lastCheckMessage: String? = null,

    @Column(name = "last_checked_at")
    var lastCheckedAt: OffsetDateTime? = null,
) {

    /**
     * Whether there is enough here to try the provider at all.
     *
     * A key is enough on its own; Entra ID needs the three things the token
     * request is made of, and no amount of one of them substitutes for another.
     *
     * A reference counts as a credential without the variable being read. What
     * this decides is whether the provider is worth checking, and a provider
     * pointed at a variable is - if the variable has gone or is still empty, the
     * check is where that gets said, in words about the variable. Reporting it
     * as "Not configured" instead would describe a provider nobody had finished
     * setting up, which is not what happened.
     */
    fun configured(): Boolean = when (authMethod) {
        // A self-hosted Laya runs without a key unless its operator set
        // LAYA_API_KEY, so for this type the key is optional and the endpoint
        // is enough to be worth asking.
        ProviderAuthMethod.API_KEY -> credentialSet() || type == ProviderType.SYSTEM_ONE
        ProviderAuthMethod.ENTRA_ID -> credentialSet() && !tenantId.isNullOrBlank() && !clientId.isNullOrBlank()
    }

    /** A copy of its own, or a variable to read one from. Never both. */
    private fun credentialSet(): Boolean = secretVariableId != null || !secret.isNullOrBlank()

    /**
     * Where this provider's OpenAI-compatible surface begins.
     *
     * Every path this application builds for a provider that is not Anthropic or
     * Azure is an OpenAI-shaped one - `/models`, `/chat/completions`,
     * `/audio/speech` - hung off whatever endpoint somebody typed. That is right
     * for every type but one. Ollama listens on `http://host:11434`, which is
     * where an operator naturally points it, and serves none of those paths
     * there: its OpenAI surface is under `/v1`, and its own listing is
     * `/api/tags`.
     *
     * So the type supplies the segment rather than the operator. `/v1/models` is
     * chosen over the native `/api/tags` because the check has to prove the
     * surface the chat will actually use: `/api/tags` answering says the Ollama
     * daemon is up and says nothing about `/v1/chat/completions` being there,
     * which is 7876cdd's failure exactly - a check that reports Connected while
     * every message 404s. It also answers in the `data[].id` shape the rest of
     * the providers do, so a discovered id is the string the chat call is given.
     *
     * An endpoint already written `.../v1` - the workaround people have been
     * using - is left as it is rather than doubled.
     */
    fun openAiBase(): String {
        val base = endpoint.trimEnd('/')
        if (type != ProviderType.OLLAMA) return base
        return if (base.endsWith(OLLAMA_OPENAI_PATH)) base else "$base$OLLAMA_OPENAI_PATH"
    }

    /**
     * Where a [ProviderType.SYSTEM_ONE] provider's API begins: the host, with
     * no `/v1` after it.
     *
     * TypeSafe documents `https://api.typesafe.ai/v1/systemone` and Laya serves
     * the same path, so what somebody pastes may be the host, the host and
     * `/v1`, or the whole call. All three mean the same provider, and the
     * paths are put back on by whoever calls it - `/v1/systemone`,
     * `/v1/models`, Laya's `/health` - rather than doubled.
     */
    fun systemOneBase(): String {
        var base = endpoint.trim().trimEnd('/')
        if (base.endsWith(SYSTEM_ONE_CALL)) base = base.removeSuffix(SYSTEM_ONE_CALL)
        if (base.endsWith(SYSTEM_ONE_VERSION)) base = base.removeSuffix(SYSTEM_ONE_VERSION)
        return base.trimEnd('/')
    }

    /** Called after anything that could change whether it is worth checking. */
    fun forgetCheck() {
        status = if (configured()) ProviderStatus.NOT_CHECKED else ProviderStatus.NOT_CONFIGURED
        lastCheckMessage = null
        lastCheckedAt = null
    }

    private companion object {
        /** Ollama's OpenAI-compatible surface, which is not where it listens. */
        const val OLLAMA_OPENAI_PATH = "/v1"

        /** The decision call, and the version segment in front of it. */
        const val SYSTEM_ONE_CALL = "/systemone"
        const val SYSTEM_ONE_VERSION = "/v1"
    }
}

/**
 * A copy of this model under a provider and a name: every setting it carries,
 * nothing it has recorded. Shared by a workspace copy and a single model's
 * duplicate, so the two cannot come to carry different things.
 */
fun LlmModel.copied(providerId: Long, name: String): LlmModel = LlmModel(
        providerId = providerId,
        name = name,
        modelId = modelId,
        kind = kind,
        contextWindow = contextWindow,
        maxOutput = maxOutput,
        parallelToolCalls = parallelToolCalls,
        reasoningEffort = reasoningEffort,
        temperature = temperature,
        topP = topP,
        topK = topK,
        minP = minP,
        repeatPenalty = repeatPenalty,
        enabled = enabled,
        tokenLimit = tokenLimit,
        resetInterval = resetInterval,
        requestsPerMinute = requestsPerMinute,
        throttleTokensPerSecond = throttleTokensPerSecond,
        throttleRequestsPerSecond = throttleRequestsPerSecond,
        acceptRetryAfter = acceptRetryAfter,
        inputCostPerMillion = inputCostPerMillion,
        outputCostPerMillion = outputCostPerMillion,
        voice = voice,
        skipEmptyLines = skipEmptyLines,
        imageCostPerImage = imageCostPerImage,
)

/**
 * A provider's settings under a new name in [workspaceId], and none of its
 * credentials or what it has recorded. Shared by a workspace copy and a single
 * provider's duplicate, so the two cannot come to carry different things; a
 * copy within the same workspace may then point at the same variable, which is
 * the caller's decision and not this one's.
 */
fun ModelProvider.copied(workspaceId: Long, name: String): ModelProvider = ModelProvider(
        workspaceId = workspaceId,
        name = name,
        type = type,
        endpoint = endpoint,
        authMethod = authMethod,
        apiVersion = apiVersion,
        deploymentName = deploymentName,
        region = region,
        tenantId = tenantId,
        clientId = clientId,
        scope = scope,
        checkEnabled = checkEnabled,
        throttleTokensPerSecond = throttleTokensPerSecond,
        throttleRequestsPerSecond = throttleRequestsPerSecond,
        acceptRetryAfter = acceptRetryAfter,
)

/**
 * One model the workspace may use, and the quotas the workspace puts on it.
 *
 * [name] is what a person calls it and [modelId] is what the provider's API is
 * given; they differ often enough — "Claude 3.5 Sonnet" against
 * `claude-3-5-sonnet-20241022` — that keeping one would lose the other.
 */
@Entity
@Table(name = "llm_model")
class LlmModel(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    /** A var because a model can be moved to another provider in its workspace, from its own page. */
    @Column(name = "provider_id", nullable = false)
    var providerId: Long,

    @Column(nullable = false, length = 120)
    var name: String,

    @Column(name = "model_id", nullable = false, length = 200)
    var modelId: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var kind: ModelKind = ModelKind.CHAT,

    @Column(name = "context_window")
    var contextWindow: Int? = null,

    @Column(name = "max_output")
    var maxOutput: Int? = null,

    /**
     * Whether one reply may ask for several tools at once. Issue #530.
     *
     * Null sends nothing and the provider decides, which is what every model
     * did until now. False is one call per reply: llama.cpp's Gemma grammar
     * allows unlimited calls whenever the request does not say otherwise, and
     * a model that has written A, B, C at temperature 1.0 finds repeating them
     * the likeliest thing to write next - one reply held 151 copies of the same
     * call before the output limit cut it off. With one call per reply that
     * reply cannot be written. True asks for several explicitly.
     *
     * Per model rather than per installation because it is a fact about a
     * model and the server running it: a hosted model that asks for three
     * things at once and stops is doing something useful.
     */
    @Column(name = "parallel_tool_calls")
    var parallelToolCalls: Boolean? = null,

    /**
     * How hard a reasoning model thinks before it answers: `minimal`, `low`,
     * `medium` or `high`, sent as `reasoning_effort`. Null sends nothing and the
     * deployment's default applies. Only a provider type that declares it in
     * [ChatParameters] may hold one - Azure OpenAI - and the save refuses it
     * anywhere else.
     */
    @Column(name = "reasoning_effort", length = 16)
    var reasoningEffort: String? = null,

    /*
     * How the model picks its words. Issue #533.
     *
     * Each is null until somebody sets it, and null sends nothing, so the
     * server's own default applies - which is what every model had until now,
     * and how a local Gemma came to run at the temperature 1.0 stored in its
     * model file without anybody choosing it. Temperature and top-p are part of
     * the OpenAI shape and every provider takes them; top-k, min-p and the
     * repeat penalty are what llama.cpp, Ollama and vLLM add, and a hosted
     * OpenAI model refuses a request carrying them - which is why each is sent
     * only when set.
     */
    @Column(name = "temperature")
    var temperature: Double? = null,

    @Column(name = "top_p")
    var topP: Double? = null,

    @Column(name = "top_k")
    var topK: Int? = null,

    @Column(name = "min_p")
    var minP: Double? = null,

    @Column(name = "repeat_penalty")
    var repeatPenalty: Double? = null,

    @Column(nullable = false)
    var enabled: Boolean = true,

    /** Null is no limit. */
    @Column(name = "token_limit")
    var tokenLimit: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "reset_interval", nullable = false, length = 16)
    var resetInterval: ResetInterval = ResetInterval.MONTHLY,

    @Column(name = "requests_per_minute")
    var requestsPerMinute: Int? = null,

    /**
     * This model's own throttle, overriding the provider's default where set.
     *
     * Limits differ per model, so the rate is the model's: every run using this
     * model shares one budget and one growing delay, and a different model of
     * the same provider shares none of it. Null on either field inherits the
     * provider's default for it; a 0 turns that dimension off though the
     * provider sets one. Distinct from [tokenLimit] and [requestsPerMinute],
     * which are a spending cap that resets, not a rate to stay under. Issue #426.
     */
    @Column(name = "throttle_tokens_per_second")
    var throttleTokensPerSecond: Long? = null,

    @Column(name = "throttle_requests_per_second")
    var throttleRequestsPerSecond: Double? = null,

    /** Null inherits the provider's choice on obeying a 429's Retry-After. Issue #426. */
    @Column(name = "accept_retry_after")
    var acceptRetryAfter: Boolean? = null,

    @Column(name = "input_cost_per_million", precision = 12, scale = 4)
    var inputCostPerMillion: BigDecimal? = null,

    @Column(name = "output_cost_per_million", precision = 12, scale = 4)
    var outputCostPerMillion: BigDecimal? = null,

    /**
     * Which voice a [ModelKind.SPEECH] model reads in.
     *
     * Null falls back to the installation's `speech-default-voice` rather than
     * sending no voice at all: the OpenAI SDK requires the field, so there is no
     * longer a way to leave the choice to the server.
     *
     * The names belong to the provider — OpenAI knows `alloy`, a local server
     * knows its own — so this is text rather than a list of options this would
     * have to keep correct for every provider that exists.
     */
    @Column(length = 80)
    var voice: String? = null,

    /**
     * Whether a [ModelKind.SPEECH] model is handed text with its empty lines
     * taken out.
     *
     * A blank line is a thing the eye reads and the ear cannot. Readers differ
     * on what to do with one - some pause for an uncomfortably long time, some
     * treat it as the end of the utterance and clip what follows, and some read
     * the answer's shape as hesitation that was never in the words. Which of
     * those happens is the reader's business and not something worth detecting,
     * so this is a switch rather than a rule.
     *
     * On the model rather than on the workspace because it is a fact about the
     * reader: the same answer sent to two speech models wants this on for one
     * and off for the other, and a workspace setting would make somebody choose
     * for both.
     *
     * False, so an installation that has not been asked reads exactly what it
     * read before. What it changes is the text handed over, never where an
     * answer is cut - the cuts are made before this, and under paragraph
     * chunking they are made *on* these lines.
     */
    @Column(name = "speech_skip_empty_lines", nullable = false)
    var skipEmptyLines: Boolean = false,

    /**
     * What one picture costs on a [ModelKind.IMAGE] model; null is not recorded.
     *
     * Its own price rather than the two beside it because these models are not
     * billed per token — they are billed per image, at a size — and an image
     * call reports no tokens at all. Costing one with [inputCostPerMillion]
     * would multiply a price by nought and report a month of drawing as free,
     * which is the one number this must never print.
     *
     * Null rather than zero for the reason [ModelPricing] gives throughout: no
     * price recorded is not a price of nothing, and a caller shows nothing where
     * it gets null.
     */
    @Column(name = "image_cost_per_image", precision = 12, scale = 4)
    var imageCostPerImage: BigDecimal? = null,
)

/**
 * What one model did on one day.
 *
 * Latency is summed rather than averaged, because an average of averages is not
 * an average: the mean over a window is the total time over the total requests.
 */
@Entity
@Table(name = "model_usage_day")
class ModelUsageDay(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "model_id", nullable = false)
    val modelId: Long,

    @Column(nullable = false)
    val day: LocalDate,

    @Column(nullable = false)
    var requests: Int = 0,

    @Column(name = "input_tokens", nullable = false)
    var inputTokens: Long = 0,

    @Column(name = "output_tokens", nullable = false)
    var outputTokens: Long = 0,

    @Column(name = "latency_millis_total", nullable = false)
    var latencyMillisTotal: Long = 0,
)

interface ModelProviderRepository : JpaRepository<ModelProvider, Long> {

    fun findByWorkspaceId(workspaceId: Long, sort: Sort): List<ModelProvider>

    fun findByWorkspaceIdAndName(workspaceId: Long, name: String): ModelProvider?

    /** Who is reading a variable, which is what stands between it and a delete. */
    fun findByWorkspaceIdAndSecretVariableId(workspaceId: Long, secretVariableId: Long): List<ModelProvider>
}

interface LlmModelRepository : JpaRepository<LlmModel, Long> {

    fun findByProviderIdIn(providerIds: Collection<Long>, sort: Sort): List<LlmModel>

    fun findByProviderIdAndName(providerId: Long, name: String): LlmModel?

    fun findByProviderId(providerId: Long): List<LlmModel>
}

interface ModelUsageRepository : JpaRepository<ModelUsageDay, Long> {

    fun findByModelIdAndDayBetweenOrderByDayAsc(
        modelId: Long,
        from: LocalDate,
        to: LocalDate,
    ): List<ModelUsageDay>

    /** The row a call adds itself to; there is one per model per day. */
    fun findByModelIdAndDay(modelId: Long, day: LocalDate): ModelUsageDay?
}

class ModelProviderNotFoundException(id: Long) : RuntimeException("No model provider with id $id")

class ModelProviderNameTakenException(name: String) :
    RuntimeException("A provider named \"$name\" already exists in this workspace")

class ModelProviderNameInvalidException : RuntimeException("A provider name is required")

class ModelProviderEndpointInvalidException : RuntimeException("A provider API endpoint is required")

class ModelNotFoundException(id: Long) : RuntimeException("No model with id $id")

class ModelNameTakenException(name: String) :
    RuntimeException("A model named \"$name\" already exists on this provider")

class ModelNameInvalidException : RuntimeException("A model name is required")

/**
 * A decision model under a provider that is not one, or anything else under a
 * provider that is. The two speak different APIs entirely - a chat model sent
 * `/v1/systemone` answers 404, and a decision model handed a conversation has
 * nothing to say - so the pairing is refused where it is made. Issue #577.
 */
class ModelKindNotOfferedException(provider: String, kind: ModelKind) : RuntimeException(
    if (kind == ModelKind.DECISION) {
        "$provider is not a decision model provider, so it has no decision models"
    } else {
        "$provider is a decision model provider, so its models are decision models"
    },
)

/** The one pairing of provider and model kind that can be called: see [ModelKindNotOfferedException]. */
fun requireKindOffered(provider: ModelProvider, kind: ModelKind) {
    if ((provider.type == ProviderType.SYSTEM_ONE) != (kind == ModelKind.DECISION)) {
        throw ModelKindNotOfferedException(provider.name, kind)
    }
}

class ModelIdInvalidException : RuntimeException("A model id is required")

/**
 * A model asked to move to a provider in another workspace.
 *
 * Refused rather than carried across: a model belongs to its workspace through
 * its provider, and every agent and setting pointing at it is that workspace's,
 * so a move across would hand one workspace's choices another's key.
 */
class ModelProviderInAnotherWorkspaceException(name: String) :
    RuntimeException("A model can only move to a provider in its own workspace, and \"$name\" is in another")

/**
 * Asking a provider what it offers did not get an answer.
 *
 * Carries the provider's own words, because "could not discover models" tells
 * nobody whether the key is wrong, the endpoint is wrong, or the box is off.
 */
class ModelDiscoveryFailedException(name: String, reason: String) :
    RuntimeException("Could not ask $name what it offers: $reason")
