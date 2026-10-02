package io.mszymanski.orknux.server.transfer

import io.mszymanski.orknux.connector.connection.AuthType
import io.mszymanski.orknux.connector.connection.ConnectionType
import io.mszymanski.orknux.connector.connection.HttpHeader
import io.mszymanski.orknux.connector.connection.MailSecurity
import io.mszymanski.orknux.connector.connection.McpServer
import io.mszymanski.orknux.connector.connection.WorkspaceConnection
import io.mszymanski.orknux.connector.model.ChatApi
import io.mszymanski.orknux.connector.model.LlmModel
import io.mszymanski.orknux.connector.model.ModelKind
import io.mszymanski.orknux.connector.model.ModelProvider
import io.mszymanski.orknux.connector.model.ProviderAuthMethod
import io.mszymanski.orknux.connector.model.ProviderType
import io.mszymanski.orknux.connector.model.ResetInterval
import io.mszymanski.orknux.server.workspace.SpeechChunking
import io.mszymanski.orknux.server.workspace.Workspace
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal

/**
 * A whole workspace in one file. Issue #590.
 *
 * What Duplicate carries ([WorkspaceDuplicator]), written down so it can be
 * carried to another installation: the workspace's own settings, its
 * connections, MCP servers, model providers and models, every component as the
 * component export writes it, and the names of its variables. The rules are the
 * component format's ([COMPONENT_FORMAT_VERSION]) and are not restated: no ids
 * travel, no secrets travel, and a version this does not know is refused rather
 * than read in part.
 *
 * **Built on the component envelope, not beside it.** Each component is a
 * shallow envelope of its own, exactly what Duplicate hands the importer, and
 * the import applies them in the duplicator's order with its retry passes - so
 * a reference inside the file resolves against what the file has already
 * brought, by name, the way it does in a copy. Each envelope keeps its own
 * format version, and is refused by the importer's own rule.
 *
 * **Written by hand, field by field**, for the reason the envelope is: a shape
 * in a file outlives the class it came from. Each field is one line in a table
 * below that both writes and reads it, so the two directions cannot disagree,
 * and a field missing from an older file is left at its default rather than
 * refused - adding one here is not a version bump.
 */
const val WORKSPACE_FILE_FORMAT: String = "orknux-workspace"

/**
 * The workspace file this installation writes, and the newest it reads.
 *
 * Compared only with `>`, like [COMPONENT_FORMAT_VERSION]. Bump it when the
 * meaning of something in the file changes; a field an older reader can ignore
 * is not a bump.
 */
const val WORKSPACE_FILE_VERSION: Int = 1

/** One model by what it is called, which is how a file names one. */
data class ModelName(val provider: String, val name: String)

/**
 * One field of an entity, written to a file and read back by one line.
 *
 * [read] leaves the entity alone where the file does not have the key - an
 * older file that never knew a field gets that field's default.
 */
internal class FileField<E, T>(
    val key: String,
    private val get: (E) -> T,
    private val set: (E, T) -> Unit,
    private val codec: Codec<T>,
) {
    fun write(from: E, into: ObjectNode) = codec.write(into, key, get(from))

    fun read(from: JsonNode, into: E) {
        if (from.has(key)) set(into, codec.read(from.get(key), key))
    }

    /** The same field from one entity onto another, for a copy that never leaves the database. */
    fun copy(from: E, into: E) = set(into, get(from))
}

/** How a value of one type is put in a file and taken out again. */
internal class Codec<T>(val write: (ObjectNode, String, T) -> Unit, val read: (JsonNode, String) -> T)

/** A value that cannot be what the file says it is, said with its key. */
private fun bad(key: String, node: JsonNode): Nothing =
    throw WorkspaceFileInvalidException("\"$key\" cannot be ${node.toString().take(40)}")

private fun <T> nullable(read: (JsonNode, String) -> T): (JsonNode, String) -> T? =
    { node, key -> if (node.isNull) null else read(node, key) }

internal val TEXT = Codec<String>({ n, k, v -> n.put(k, v) }, { n, k -> if (n.isString) n.asString() else bad(k, n) })
internal val TEXT_OR_NULL = Codec<String?>({ n, k, v -> n.put(k, v) }, nullable(TEXT.read))
internal val WHOLE_OR_NULL = Codec<Int?>(
    { n, k, v -> if (v == null) n.putNull(k) else n.put(k, v) },
    nullable { n, k -> if (n.canConvertToInt() && n.isIntegralNumber) n.asInt() else bad(k, n) },
)
internal val LONG_OR_NULL = Codec<Long?>(
    { n, k, v -> if (v == null) n.putNull(k) else n.put(k, v) },
    nullable { n, k -> if (n.isIntegralNumber) n.asLong() else bad(k, n) },
)
internal val NUMBER_OR_NULL = Codec<Double?>(
    { n, k, v -> if (v == null) n.putNull(k) else n.put(k, v) },
    nullable { n, k -> if (n.isNumber) n.asDouble() else bad(k, n) },
)
/** Money, kept as text so nothing is rounded on the way through a double. */
internal val DECIMAL_OR_NULL = Codec<BigDecimal?>(
    { n, k, v -> n.put(k, v?.toPlainString()) },
    nullable { n, k -> runCatching { BigDecimal(n.asString()) }.getOrElse { bad(k, n) } },
)
internal val YES_NO = Codec<Boolean>({ n, k, v -> n.put(k, v) }, { n, k -> if (n.isBoolean) n.asBoolean() else bad(k, n) })
internal val YES_NO_OR_NULL = Codec<Boolean?>(
    { n, k, v -> if (v == null) n.putNull(k) else n.put(k, v) },
    nullable(YES_NO.read),
)

/** An enum by its constant's name; one this installation does not have is refused by name. */
internal inline fun <reified T : Enum<T>> choice() = Codec<T>(
    { n, k, v -> n.put(k, v.name) },
    { n, k -> enumValues<T>().firstOrNull { it.name == n.asString() } ?: bad(k, n) },
)

internal inline fun <reified T : Enum<T>> choiceOrNull() = Codec<T?>(
    { n, k, v -> n.put(k, v?.name) },
    nullable { n, k -> enumValues<T>().firstOrNull { it.name == n.asString() } ?: bad(k, n) },
)

/** Extra headers, as name and value. A header is the workspace's own text, like an action's. */
internal val HEADERS = Codec<MutableList<HttpHeader>>(
    { n, k, v -> n.putArray(k).also { array -> v.forEach { array.addObject().put("name", it.name).put("value", it.value) } } },
    { n, k ->
        if (!n.isArray) bad(k, n)
        n.values().map { HttpHeader(it.path("name").asString(""), it.path("value").asString("")) }.toMutableList()
    },
)

/**
 * The workspace's own settings, which Duplicate carries and a file holds.
 *
 * Not its name - a copy is given one - and not its roles: who may see a
 * workspace is a decision about people, not about the setup. Not the model
 * choices either, which are ids and are carried by name in [WORKSPACE_MODEL_CHOICES].
 * `WorkspaceDuplicateTest` lists every field of [Workspace] as here or left on
 * purpose, so one added to the entity has to be decided on.
 */
internal val WORKSPACE_SETTINGS: List<FileField<Workspace, *>> = listOf(
    FileField("description", { it.description }, { w, v -> w.description = v }, TEXT_OR_NULL),
    FileField("compactAfterTokens", { it.compactAfterTokens }, { w, v -> w.compactAfterTokens = v }, WHOLE_OR_NULL),
    FileField("compactionSummaryTokens", { it.compactionSummaryTokens }, { w, v -> w.compactionSummaryTokens = v }, WHOLE_OR_NULL),
    FileField("quickChatMayWrite", { it.quickChatMayWrite }, { w, v -> w.quickChatMayWrite = v }, YES_NO),
    FileField("chatShowTimestamps", { it.chatShowTimestamps }, { w, v -> w.chatShowTimestamps = v }, YES_NO),
    FileField("defaultMemoryShare", { it.defaultMemoryShare }, { w, v -> w.defaultMemoryShare = v }, WHOLE_OR_NULL),
    FileField("taskMaxTurns", { it.taskMaxTurns }, { w, v -> w.taskMaxTurns = v }, WHOLE_OR_NULL),
    FileField("agentMaxSubagents", { it.agentMaxSubagents }, { w, v -> w.agentMaxSubagents = v }, WHOLE_OR_NULL),
    FileField("maxRepeatedToolCalls", { it.maxRepeatedToolCalls }, { w, v -> w.maxRepeatedToolCalls = v }, WHOLE_OR_NULL),
    FileField("maxToolCallsAtOnce", { it.maxToolCallsAtOnce }, { w, v -> w.maxToolCallsAtOnce = v }, WHOLE_OR_NULL),
    FileField("sessionCompactAfterTokens", { it.sessionCompactAfterTokens }, { w, v -> w.sessionCompactAfterTokens = v }, WHOLE_OR_NULL),
    FileField("sessionCompactionKeepTurns", { it.sessionCompactionKeepTurns }, { w, v -> w.sessionCompactionKeepTurns = v }, WHOLE_OR_NULL),
    FileField("sessionCompactionSummaryTokens", { it.sessionCompactionSummaryTokens }, { w, v -> w.sessionCompactionSummaryTokens = v }, WHOLE_OR_NULL),
    FileField("sessionCompactionAttempts", { it.sessionCompactionAttempts }, { w, v -> w.sessionCompactionAttempts = v }, WHOLE_OR_NULL),
    FileField("repeatedToolCallsWindowSeconds", { it.repeatedToolCallsWindowSeconds }, { w, v -> w.repeatedToolCallsWindowSeconds = v }, WHOLE_OR_NULL),
    FileField("repeatedToolCallWarnings", { it.repeatedToolCallWarnings }, { w, v -> w.repeatedToolCallWarnings = v }, WHOLE_OR_NULL),
    FileField("unsafeBuiltInTools", { it.unsafeBuiltInTools }, { w, v -> w.unsafeBuiltInTools = v }, YES_NO),
    FileField("commandMarker", { it.commandMarker }, { w, v -> w.commandMarker = v }, TEXT_OR_NULL),
    FileField("functionTimeoutSeconds", { it.functionTimeoutSeconds }, { w, v -> w.functionTimeoutSeconds = v }, WHOLE_OR_NULL),
    FileField("toolTimeoutSeconds", { it.toolTimeoutSeconds }, { w, v -> w.toolTimeoutSeconds = v }, WHOLE_OR_NULL),
    FileField("voicePauseEndsTurnMs", { it.voicePauseEndsTurnMs }, { w, v -> w.voicePauseEndsTurnMs = v }, WHOLE_OR_NULL),
    FileField("voiceSpeechOverRoomPercent", { it.voiceSpeechOverRoomPercent }, { w, v -> w.voiceSpeechOverRoomPercent = v }, WHOLE_OR_NULL),
    FileField("voiceUnattendedMicrophoneMs", { it.voiceUnattendedMicrophoneMs }, { w, v -> w.voiceUnattendedMicrophoneMs = v }, WHOLE_OR_NULL),
    FileField("voiceBargeInMs", { it.voiceBargeInMs }, { w, v -> w.voiceBargeInMs = v }, WHOLE_OR_NULL),
    FileField("voiceSpeechChunking", { it.voiceSpeechChunking }, { w, v -> w.voiceSpeechChunking = v }, choice<SpeechChunking>()),
)

/**
 * Which of the workspace's models it uses for what, each an id here and a
 * [ModelName] anywhere else. A copy points each at the model it copied under
 * the same names; one that names a model not carried is left unset.
 */
internal class ModelChoice(val key: String, val get: (Workspace) -> Long?, val set: (Workspace, Long?) -> Unit)

internal val WORKSPACE_MODEL_CHOICES: List<ModelChoice> = listOf(
    ModelChoice("companion", { it.companionModelId }, { w, v -> w.companionModelId = v }),
    ModelChoice("transcription", { it.transcriptionModelId }, { w, v -> w.transcriptionModelId = v }),
    ModelChoice("speech", { it.speechModelId }, { w, v -> w.speechModelId = v }),
    ModelChoice("compaction", { it.compactionModelId }, { w, v -> w.compactionModelId = v }),
    ModelChoice("image", { it.imageModelId }, { w, v -> w.imageModelId = v }),
    ModelChoice("quickChat", { it.quickChatModelId }, { w, v -> w.quickChatModelId = v }),
    ModelChoice("sessionCompaction", { it.sessionCompactionModelId }, { w, v -> w.sessionCompactionModelId = v }),
)

/*
 * The externals, as Duplicate copies them: the same fields, and the same ones
 * left behind - the credential and the variable it may be read from, and what
 * the last check found. The name, and what each row cannot be made without, are
 * written apart from these tables because they are the constructor's.
 */

internal val CONNECTION_FIELDS: List<FileField<WorkspaceConnection, *>> = listOf(
    FileField("urlOverride", { it.urlOverride }, { c, v -> c.urlOverride = v }, TEXT_OR_NULL),
    FileField("pluginType", { it.pluginType }, { c, v -> c.pluginType = v }, TEXT_OR_NULL),
    FileField("authType", { it.authType }, { c, v -> c.authType = v }, choice<AuthType>()),
    FileField("smtpPort", { it.smtpPort }, { c, v -> c.smtpPort = v }, WHOLE_OR_NULL),
    FileField("smtpUsername", { it.smtpUsername }, { c, v -> c.smtpUsername = v }, TEXT_OR_NULL),
    FileField("smtpFrom", { it.smtpFrom }, { c, v -> c.smtpFrom = v }, TEXT_OR_NULL),
    FileField("smtpSecurity", { it.smtpSecurity }, { c, v -> c.smtpSecurity = v }, choice<MailSecurity>()),
    FileField("headers", { it.headers }, { c, v -> c.headers = v }, HEADERS),
)

internal val MCP_SERVER_FIELDS: List<FileField<McpServer, *>> = listOf(
    FileField("authType", { it.authType }, { s, v -> s.authType = v }, choice<AuthType>()),
    FileField("headers", { it.headers }, { s, v -> s.headers = v }, HEADERS),
)

internal val PROVIDER_FIELDS: List<FileField<ModelProvider, *>> = listOf(
    FileField("type", { it.type }, { p, v -> p.type = v }, choice<ProviderType>()),
    FileField("authMethod", { it.authMethod }, { p, v -> p.authMethod = v }, choice<ProviderAuthMethod>()),
    FileField("apiVersion", { it.apiVersion }, { p, v -> p.apiVersion = v }, TEXT_OR_NULL),
    FileField("deploymentName", { it.deploymentName }, { p, v -> p.deploymentName = v }, TEXT_OR_NULL),
    FileField("region", { it.region }, { p, v -> p.region = v }, TEXT_OR_NULL),
    FileField("tenantId", { it.tenantId }, { p, v -> p.tenantId = v }, TEXT_OR_NULL),
    FileField("clientId", { it.clientId }, { p, v -> p.clientId = v }, TEXT_OR_NULL),
    FileField("scope", { it.scope }, { p, v -> p.scope = v }, TEXT_OR_NULL),
    FileField("checkEnabled", { it.checkEnabled }, { p, v -> p.checkEnabled = v }, YES_NO),
    FileField("throttleTokensPerSecond", { it.throttleTokensPerSecond }, { p, v -> p.throttleTokensPerSecond = v }, LONG_OR_NULL),
    FileField("throttleRequestsPerSecond", { it.throttleRequestsPerSecond }, { p, v -> p.throttleRequestsPerSecond = v }, NUMBER_OR_NULL),
    FileField("acceptRetryAfter", { it.acceptRetryAfter }, { p, v -> p.acceptRetryAfter = v }, YES_NO),
    FileField("chatApi", { it.chatApi }, { p, v -> p.chatApi = v }, choiceOrNull<ChatApi>()),
)

internal val MODEL_FIELDS: List<FileField<LlmModel, *>> = listOf(
    FileField("kind", { it.kind }, { m, v -> m.kind = v }, choice<ModelKind>()),
    FileField("contextWindow", { it.contextWindow }, { m, v -> m.contextWindow = v }, WHOLE_OR_NULL),
    FileField("maxOutput", { it.maxOutput }, { m, v -> m.maxOutput = v }, WHOLE_OR_NULL),
    FileField("parallelToolCalls", { it.parallelToolCalls }, { m, v -> m.parallelToolCalls = v }, YES_NO_OR_NULL),
    FileField("reasoningEffort", { it.reasoningEffort }, { m, v -> m.reasoningEffort = v }, TEXT_OR_NULL),
    FileField("temperature", { it.temperature }, { m, v -> m.temperature = v }, NUMBER_OR_NULL),
    FileField("topP", { it.topP }, { m, v -> m.topP = v }, NUMBER_OR_NULL),
    FileField("topK", { it.topK }, { m, v -> m.topK = v }, WHOLE_OR_NULL),
    FileField("minP", { it.minP }, { m, v -> m.minP = v }, NUMBER_OR_NULL),
    FileField("repeatPenalty", { it.repeatPenalty }, { m, v -> m.repeatPenalty = v }, NUMBER_OR_NULL),
    FileField("enabled", { it.enabled }, { m, v -> m.enabled = v }, YES_NO),
    FileField("tokenLimit", { it.tokenLimit }, { m, v -> m.tokenLimit = v }, LONG_OR_NULL),
    FileField("resetInterval", { it.resetInterval }, { m, v -> m.resetInterval = v }, choice<ResetInterval>()),
    FileField("requestsPerMinute", { it.requestsPerMinute }, { m, v -> m.requestsPerMinute = v }, WHOLE_OR_NULL),
    FileField("throttleTokensPerSecond", { it.throttleTokensPerSecond }, { m, v -> m.throttleTokensPerSecond = v }, LONG_OR_NULL),
    FileField("throttleRequestsPerSecond", { it.throttleRequestsPerSecond }, { m, v -> m.throttleRequestsPerSecond = v }, NUMBER_OR_NULL),
    FileField("acceptRetryAfter", { it.acceptRetryAfter }, { m, v -> m.acceptRetryAfter = v }, YES_NO_OR_NULL),
    FileField("inputCostPerMillion", { it.inputCostPerMillion }, { m, v -> m.inputCostPerMillion = v }, DECIMAL_OR_NULL),
    FileField("outputCostPerMillion", { it.outputCostPerMillion }, { m, v -> m.outputCostPerMillion = v }, DECIMAL_OR_NULL),
    FileField("voice", { it.voice }, { m, v -> m.voice = v }, TEXT_OR_NULL),
    FileField("skipEmptyLines", { it.skipEmptyLines }, { m, v -> m.skipEmptyLines = v }, YES_NO),
    FileField("imageCostPerImage", { it.imageCostPerImage }, { m, v -> m.imageCostPerImage = v }, DECIMAL_OR_NULL),
)

/** A connection as a file holds it; [inherits] names the installation's default it reads, if any. */
internal fun connectionNode(held: WorkspaceConnection, inherits: String?, credentialNeeded: Boolean, into: ObjectNode) {
    into.put("name", held.name)
    into.put("type", held.type.name)
    into.put("url", held.url)
    into.put("inherits", inherits)
    into.put("credentialNeeded", credentialNeeded)
    CONNECTION_FIELDS.forEach { it.write(held, into) }
}

internal fun connectionFrom(node: JsonNode, workspaceId: Long, connectionId: Long?): WorkspaceConnection {
    val name = node.required("name", "A connection")
    return WorkspaceConnection(
        workspaceId = workspaceId,
        connectionId = connectionId,
        name = name,
        type = choice<ConnectionType>().read(node.path("type"), "connection $name type"),
        url = node.required("url", "Connection $name"),
    ).also { made -> CONNECTION_FIELDS.forEach { it.read(node, made) } }
}

internal fun mcpServerNode(held: McpServer, credentialNeeded: Boolean, into: ObjectNode) {
    into.put("name", held.name)
    into.put("address", held.address)
    into.put("credentialNeeded", credentialNeeded)
    MCP_SERVER_FIELDS.forEach { it.write(held, into) }
}

internal fun mcpServerFrom(node: JsonNode, workspaceId: Long): McpServer {
    val name = node.required("name", "An MCP server")
    return McpServer(workspaceId = workspaceId, name = name, address = node.required("address", "MCP server $name"))
        .also { made -> MCP_SERVER_FIELDS.forEach { it.read(node, made) } }
}

internal fun providerNode(held: ModelProvider, credentialNeeded: Boolean, into: ObjectNode) {
    into.put("name", held.name)
    into.put("endpoint", held.endpoint)
    into.put("credentialNeeded", credentialNeeded)
    PROVIDER_FIELDS.forEach { it.write(held, into) }
}

internal fun providerFrom(node: JsonNode, workspaceId: Long): ModelProvider {
    val name = node.required("name", "A model provider")
    return ModelProvider(workspaceId = workspaceId, name = name, endpoint = node.required("endpoint", "Model provider $name"))
        .also { made -> PROVIDER_FIELDS.forEach { it.read(node, made) } }
}

internal fun modelNode(held: LlmModel, into: ObjectNode) {
    into.put("name", held.name)
    into.put("modelId", held.modelId)
    MODEL_FIELDS.forEach { it.write(held, into) }
}

internal fun modelFrom(node: JsonNode, providerId: Long, provider: String): LlmModel {
    val name = node.required("name", "A model of $provider")
    return LlmModel(providerId = providerId, name = name, modelId = node.required("modelId", "Model $provider / $name"))
        .also { made -> MODEL_FIELDS.forEach { it.read(node, made) } }
}

/** One component of a file: its kind, its name, and the envelope that carries it. */
internal class FileComponent(val kind: ComponentKind, val name: String, val envelope: JsonNode)

/**
 * A workspace file, checked whole before anything in it is created.
 *
 * Every row is read once into a throwaway entity here, so a value this
 * installation cannot hold - a provider type it has never heard of - refuses
 * the file before a workspace exists, rather than stopping a copy halfway.
 */
internal class WorkspaceFileContent(
    val name: String,
    val producedBy: String?,
    val settings: JsonNode,
    val models: Map<String, ModelName>,
    val connections: List<JsonNode>,
    val mcpServers: List<JsonNode>,
    val providers: List<JsonNode>,
    val components: List<FileComponent>,
    val variables: List<String>,
)

internal fun readWorkspaceFile(mapper: tools.jackson.databind.ObjectMapper, content: String): WorkspaceFileContent {
    val root = try {
        mapper.readTree(content)
    } catch (cause: tools.jackson.core.JacksonException) {
        throw WorkspaceFileUnreadableException("it is not valid JSON")
    }
    if (root == null || !root.isObject) throw WorkspaceFileUnreadableException("it does not hold a JSON object")
    val format = root.path("format").asString("")
    if (format != WORKSPACE_FILE_FORMAT) {
        // The one mistake worth naming: a component export offered as a workspace.
        if (root.has("roots") && root.has("components")) {
            throw WorkspaceFileUnreadableException("it is a component export; import it on a workspace's page instead")
        }
        throw WorkspaceFileUnreadableException("it says nothing about being one")
    }
    val producedBy = root.path("producedBy").takeIf { it.isString }?.asString()
    val version = root.path("formatVersion")
    if (!version.isIntegralNumber || version.asInt() < 1) {
        throw WorkspaceFileUnreadableException("it carries no format version, so there is no telling how to read it")
    }
    if (version.asInt() > WORKSPACE_FILE_VERSION) throw WorkspaceFileVersionUnknownException(version.asInt(), producedBy)

    val workspace = root.path("workspace")
    val name = workspace.required("name", "The workspace")
    val settings = workspace.path("settings")
    Workspace(name = name).also { trial -> WORKSPACE_SETTINGS.forEach { it.read(settings, trial) } }
    val models = WORKSPACE_MODEL_CHOICES.mapNotNull { choice ->
        val chosen = workspace.path("models").path(choice.key)
        if (!chosen.isObject) return@mapNotNull null
        choice.key to ModelName(chosen.required("provider", "The ${choice.key} model"), chosen.required("name", "The ${choice.key} model"))
    }.toMap()

    val connections = root.path("connections").values().toList()
    val mcpServers = root.path("mcpServers").values().toList()
    val providers = root.path("modelProviders").values().toList()
    connections.forEach { connectionFrom(it, 0, null) }
    mcpServers.forEach { mcpServerFrom(it, 0) }
    providers.forEach { provider ->
        val made = providerFrom(provider, 0)
        provider.path("models").values().forEach { modelFrom(it, 0, made.name) }
    }
    listOf("connection" to connections, "MCP server" to mcpServers, "model provider" to providers).forEach { (what, rows) ->
        rows.groupBy { it.path("name").asString() }.filterValues { it.size > 1 }.keys.firstOrNull()?.let {
            throw WorkspaceFileInvalidException("it holds two of the ${what}s called $it")
        }
    }

    val components = root.path("components").values().mapIndexed { at, envelope ->
        val root0 = envelope.path("roots").path(0)
        val kind = ComponentKind.entries.firstOrNull { it.name == root0.path("kind").asString("") }
            ?: throw WorkspaceFileInvalidException("component ${at + 1} is of a kind this installation does not import")
        val called = root0.required("name", "Component ${at + 1}")
        val itsVersion = envelope.path("formatVersion").asInt(0)
        if (itsVersion < 1 || itsVersion > COMPONENT_FORMAT_VERSION) {
            throw WorkspaceFileInvalidException(
                "the ${kind.label} $called is component format version $itsVersion, and this installation reads up to " +
                    "$COMPONENT_FORMAT_VERSION",
            )
        }
        FileComponent(kind, called, envelope)
    }
    val variables = root.path("variables").values().mapNotNull { it.takeIf { v -> v.isString }?.asString() }

    return WorkspaceFileContent(name, producedBy, settings, models, connections, mcpServers, providers, components, variables)
}

/** A required piece of text off a node, or the file refused naming what lacked it. */
internal fun JsonNode.required(key: String, what: String): String =
    path(key).takeIf { it.isString }?.asString()?.takeIf { it.isNotBlank() }
        ?: throw WorkspaceFileInvalidException("$what has no $key")

/** The file is not JSON, or not a workspace export at all. */
class WorkspaceFileUnreadableException(val reason: String) :
    RuntimeException("This file is not an Orknux workspace export: $reason."), io.mszymanski.orknux.server.graphql.Refusal {

    override val arguments get() = mapOf("reason" to reason)
}

/**
 * The file was written by a newer Orknux than this one. Refused whole, for the
 * reason a newer component envelope is: what this could read out of it would
 * be a workspace missing whatever the newer version added, with nothing to say
 * which parts.
 */
class WorkspaceFileVersionUnknownException(val found: Int, val producedBy: String?) : RuntimeException(
    "This workspace export is format version $found and this installation reads up to version " +
        "$WORKSPACE_FILE_VERSION" + (producedBy?.let { "; it was produced by $it" } ?: "") +
        ". Upgrade this installation, or export again from one of this version.",
), io.mszymanski.orknux.server.graphql.Refusal {

    override val arguments get() = mapOf("found" to found, "reads" to WORKSPACE_FILE_VERSION, "producedBy" to (producedBy ?: ""))
}

/** The file is a workspace export, and says something that cannot be so. */
class WorkspaceFileInvalidException(val says: String) : RuntimeException(
    "This workspace export cannot be imported: $says. Nothing was imported.",
), io.mszymanski.orknux.server.graphql.Refusal {

    override val arguments get() = mapOf("says" to says)
}
