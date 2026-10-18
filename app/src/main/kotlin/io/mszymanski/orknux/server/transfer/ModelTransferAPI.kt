package io.mszymanski.orknux.server.transfer

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProvider
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.connector.model.requireKindOffered
import io.mszymanski.orknux.server.graphql.refused
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.Sort
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** What a models file says it is. */
const val MODELS_FILE_FORMAT: String = "orknux-models"

/** The models file this installation writes, and the newest it reads; compared only with `>`. */
const val MODELS_FILE_VERSION: Int = 1

/**
 * A workspace's models, carried to another workspace or installation as a file.
 *
 * The models chosen, each under its provider - endpoint, type and every setting
 * the workspace file carries, through the same field tables, so the two cannot
 * disagree. No key travels: a provider that held one is marked as needing one,
 * and arrives without. Importing matches a provider by name and reuses one this
 * workspace already has, and leaves a model alone where that provider already
 * has one of that name - so importing the same file twice changes nothing.
 */
@Controller
class ModelTransferAPI(
    private val providers: ModelProviderRepository,
    private val models: LlmModelRepository,
    private val access: WorkspaceAccess,
    private val audit: WorkspaceAuditRecorder,
    private val mapper: ObjectMapper,
    @Value("\${orknux.version:unknown}") private val version: String,
) {

    /** The chosen models, or all of them, as a file; [models] is a comma-separated list of ids. */
    @GetMapping("/api/workspaces/{workspaceId}/models/export")
    @ResponseBody
    // Not read-only: the export is audited.
    @Transactional
    fun export(@PathVariable workspaceId: Long, @RequestParam(required = false) models: String?): ResponseEntity<ByteArray> {
        access.requireVisible(workspaceId)
        val wanted = models?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.toSet()?.takeIf { it.isNotEmpty() }
        val root = mapper.createObjectNode()
        root.put("format", MODELS_FILE_FORMAT)
        root.put("formatVersion", MODELS_FILE_VERSION)
        // Only ever quoted back in a message; nothing branches on it.
        root.put("producedBy", "Orknux $version")
        root.put("exportedAt", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        val rows = root.putArray("modelProviders")
        var count = 0
        providers.findByWorkspaceId(workspaceId, Sort.by("name")).forEach { held ->
            val chosen = this.models.findByProviderId(requireNotNull(held.id))
                .filter { wanted == null || it.id in wanted }
                .sortedBy { it.name }
            if (chosen.isEmpty()) return@forEach
            val row = rows.addObject()
            providerNode(held, held.secretVariableId != null || !held.secret.isNullOrBlank(), row)
            val modelRows = row.putArray("models")
            chosen.forEach { modelNode(it, modelRows.addObject()) }
            count += chosen.size
        }
        if (count == 0) throw ModelFileRefusedException("There are no such models in this workspace to export.")
        audit.record(workspaceId, WorkspaceAuditCategory.MODEL, "$count model${if (count == 1) "" else "s"} exported")
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename("models.orkx.json", StandardCharsets.UTF_8).build().toString(),
            )
            .body(mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root))
    }

    /**
     * A models file read into this workspace. Checked whole first - a type this
     * installation does not know, or a model kind its provider cannot hold,
     * refuses the file before anything is made.
     */
    @MutationMapping
    @Transactional
    fun importModels(@Argument workspaceId: Long, @Argument content: String): ModelImportView {
        access.requireVisible(workspaceId)
        val root = runCatching { mapper.readTree(content) }.getOrNull()
            ?: throw ModelFileRefusedException("That file could not be read as a models file.")
        if (root.path("format").asString("") != MODELS_FILE_FORMAT) {
            throw ModelFileRefusedException("That is not a models file.")
        }
        val fileVersion = root.path("formatVersion").asInt(0)
        if (fileVersion > MODELS_FILE_VERSION) {
            throw ModelFileRefusedException(
                "That file was written by a newer Orknux (models file version $fileVersion); this one reads up to $MODELS_FILE_VERSION.",
            )
        }
        val rows: List<JsonNode> = root.path("modelProviders").values().toList()

        // Every row read once into a throwaway entity, so a value this installation cannot hold refuses the file whole.
        rows.forEach { node ->
            val provider = read("model provider") { providerFrom(node, workspaceId) }
            node.path("models").values().forEach { model ->
                val made = read("model of ${provider.name}") { modelFrom(model, 0, provider.name) }
                runCatching { requireKindOffered(provider, made.kind) }
                    .onFailure { throw ModelFileRefusedException(it.message ?: "A model in that file cannot be held by its provider.") }
            }
        }

        val createdProviders = mutableListOf<String>()
        val needsKey = mutableListOf<String>()
        val createdModels = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        rows.forEach { node ->
            val name = node.required("name", "A model provider")
            val provider: ModelProvider = providers.findByWorkspaceIdAndName(workspaceId, name)
                ?: providers.save(providerFrom(node, workspaceId)).also {
                    createdProviders += it.name
                    if (node.path("credentialNeeded").asBoolean(false)) needsKey += it.name
                }
            node.path("models").values().forEach { model ->
                val modelName = model.required("name", "A model of ${provider.name}")
                if (models.findByProviderIdAndName(requireNotNull(provider.id), modelName) != null) {
                    skipped += "${provider.name} / $modelName"
                } else {
                    models.save(modelFrom(model, requireNotNull(provider.id), provider.name))
                    createdModels += "${provider.name} / $modelName"
                }
            }
        }
        if (createdModels.isNotEmpty() || createdProviders.isNotEmpty()) {
            audit.record(
                workspaceId,
                WorkspaceAuditCategory.MODEL,
                "${createdModels.size} model${if (createdModels.size == 1) "" else "s"} imported" +
                    if (createdProviders.isEmpty()) "" else " with ${createdProviders.joinToString()}",
            )
        }
        return ModelImportView(createdProviders, needsKey, createdModels, skipped)
    }

    private fun <T> read(what: String, block: () -> T): T = try {
        block()
    } catch (refusal: ModelFileRefusedException) {
        throw refusal
    } catch (failure: RuntimeException) {
        throw ModelFileRefusedException("A $what in that file could not be read: ${failure.message}")
    }
}

/** What an import made, and what it left alone. */
data class ModelImportView(
    /** Providers made, by name; a provider of that name already here is used instead. */
    val providers: List<String>,
    /** Of those, the ones that held a key where they came from and now need one. */
    val needsKey: List<String>,
    /** Models made, as provider / model. */
    val models: List<String>,
    /** Models left alone because their provider here already has one of that name. */
    val skipped: List<String>,
)

/** Why a models file was not exported or read, in the words the page shows. */
class ModelFileRefusedException(reason: String) : RuntimeException(reason)

@Component
class ModelTransferExceptionResolver : DataFetcherExceptionResolverAdapter() {

    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? {
        val errorType = when (exception) {
            is ModelFileRefusedException -> ErrorType.BAD_REQUEST
            else -> return null
        }
        return refused(exception, errorType, environment)
    }
}
