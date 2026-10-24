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
import org.springframework.graphql.data.method.annotation.QueryMapping
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
    private val templates: ComponentTemplateService,
) {

    /**
     * The chosen models, or all of them, as a file; [models] and [providers] are
     * comma-separated lists of ids. A provider named in [providers] travels with
     * every model it has, and travels even with none - exporting a provider is
     * exporting the provider, not only what happens to hang off it.
     */
    @GetMapping("/api/workspaces/{workspaceId}/models/export")
    @ResponseBody
    // Not read-only: the export is audited.
    @Transactional
    fun export(
        @PathVariable workspaceId: Long,
        @RequestParam(required = false) models: String?,
        @RequestParam(required = false) providers: String?,
    ): ResponseEntity<ByteArray> {
        access.requireVisible(workspaceId)
        fun ids(list: String?) = list?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.toSet()?.takeIf { it.isNotEmpty() }
        val wantedProviders = ids(providers)
        val (root, count) = modelsFile(workspaceId, ids(models), wantedProviders)
        val shipped = root.path("modelProviders").values().map { it.path("name").asString("") }
        audit.record(
            workspaceId,
            WorkspaceAuditCategory.MODEL,
            if (wantedProviders != null) "Model provider${if (shipped.size == 1) "" else "s"} ${shipped.joinToString()} exported"
            else "$count model${if (count == 1) "" else "s"} exported",
        )
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename("models.orkx.json", StandardCharsets.UTF_8).build().toString(),
            )
            .body(mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root))
    }

    /**
     * The chosen models, or all of them, under their providers and without any
     * key - the file an export downloads and a template of models holds.
     */
    private fun modelsFile(
        workspaceId: Long,
        wanted: Set<Long>?,
        wantedProviders: Set<Long>? = null,
    ): Pair<tools.jackson.databind.node.ObjectNode, Int> {
        val root = mapper.createObjectNode()
        root.put("format", MODELS_FILE_FORMAT)
        root.put("formatVersion", MODELS_FILE_VERSION)
        // Only ever quoted back in a message; nothing branches on it.
        root.put("producedBy", "Orknux $version")
        root.put("exportedAt", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        val rows = root.putArray("modelProviders")
        var count = 0
        var providerCount = 0
        providers.findByWorkspaceId(workspaceId, Sort.by("name")).forEach { held ->
            if (wantedProviders != null && held.id !in wantedProviders) return@forEach
            val chosen = this.models.findByProviderId(requireNotNull(held.id))
                .filter { wantedProviders != null || wanted == null || it.id in wanted }
                .sortedBy { it.name }
            if (chosen.isEmpty() && wantedProviders == null) return@forEach
            providerCount++
            val row = rows.addObject()
            providerNode(held, held.secretVariableId != null || !held.secret.isNullOrBlank(), row)
            val modelRows = row.putArray("models")
            chosen.forEach { modelNode(it, modelRows.addObject()) }
            count += chosen.size
        }
        if (wantedProviders != null && providerCount == 0) {
            throw ModelFileRefusedException("There are no such model providers in this workspace to export.")
        }
        if (wantedProviders == null && count == 0) throw ModelFileRefusedException("There are no such models in this workspace to export.")
        return root to count
    }

    /**
     * Publishes the chosen models as a template, for every workspace's Models
     * page to offer. Administrators, as publishing any template is. No key
     * travels: a provider that held one arrives needing one, as from a file.
     */
    @MutationMapping
    @Transactional
    fun publishModelTemplate(
        @Argument workspaceId: Long,
        @Argument modelIds: List<Long>,
        @Argument name: String,
        @Argument description: String?,
    ): ComponentTemplateView {
        access.requireAdmin()
        access.requireVisible(workspaceId)
        val (root, count) = modelsFile(workspaceId, modelIds.toSet())
        val made = templates.create(name, description, mapper.writeValueAsString(root))
        audit.record(null, WorkspaceAuditCategory.WORKSPACE, "Template ${made.name} added, holding $count model${if (count == 1) "" else "s"}")
        return made
    }

    /** The templates of models, for the Models page's Use a template. Signed in, as every template list is. */
    @QueryMapping
    fun modelTemplates(): List<ComponentTemplateView> {
        access.requireSignedIn()
        return templates.modelTemplates()
    }

    /** A template of models read into this workspace, exactly as importing its file would. */
    @MutationMapping
    @Transactional
    fun useModelTemplate(@Argument workspaceId: Long, @Argument id: Long): ModelImportView {
        val template = templates.template(id)?.takeIf { it.ofModels } ?: throw ModelFileRefusedException("There is no template of models $id.")
        return importModels(workspaceId, templates.envelopeOf(requireNotNull(template.id)))
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
