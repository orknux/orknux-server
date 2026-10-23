package io.mszymanski.orknux.server.transfer

import org.springframework.data.repository.findByIdOrNull
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * The installation's templates: stored envelopes, described and handed back.
 *
 * Everything that decides what a template *is* happens in two places that are
 * not here — [ComponentExporter] writes the envelope and [ComponentImporter]
 * reads it — and this holds the rows in between. What it does add is the rule
 * that a template is validated when it is published rather than when somebody
 * tries to use it: an envelope that cannot be read is refused at the New
 * Template screen, which is the moment there is somebody present who can fix it.
 *
 * The one case that survives that is a file which was readable when it was
 * published and is not now, which happens when an installation is rolled back
 * past the format version its templates were written under. That is why
 * [view] catches rather than throws: the row is still shown, with its name and
 * the refusal in words, instead of the page failing to load because one of forty
 * templates is from the future.
 */
@Service
class ComponentTemplateService(
    private val templates: ComponentTemplateRepository,
    private val importer: ComponentImporter,
    private val mapper: tools.jackson.databind.ObjectMapper,
) {

    /**
     * Whether a stored file is a models file rather than a component envelope.
     * A model is not a component - it belongs to a provider and carries no
     * graph - so its template holds the models file the Models page exports,
     * and is used by reading that file in, the way an import of one is.
     */
    private fun ofModels(envelope: String): Boolean =
        runCatching { mapper.readTree(envelope).path("format").asString("") == MODELS_FILE_FORMAT }.getOrDefault(false)

    /**
     * Whether a stored file is an integrations file: connections and MCP
     * servers, which are a workspace's settings rather than components, held
     * the way a workspace file holds them and used the way a models file is.
     */
    private fun ofIntegrations(envelope: String): Boolean =
        runCatching { mapper.readTree(envelope).path("format").asString("") == INTEGRATIONS_FILE_FORMAT }.getOrDefault(false)

    /** Every template of connections and MCP servers, by name: what the Integrations page offers. */
    fun integrationTemplates(): List<ComponentTemplateView> =
        templates.findAllByOrderByNameAsc().filter { ofIntegrations(it.envelope) }.map(::view)

    /** Every template of models, by name: what the Models page offers. */
    fun modelTemplates(): List<ComponentTemplateView> =
        templates.findAllByOrderByNameAsc().filter { ofModels(it.envelope) }.map(::view)

    /**
     * Every template, by name.
     *
     * [holding] narrows it to the ones that carry that kind, which is what the
     * "Use template" button on a page of functions asks for — a template of
     * nothing but skills is not an answer to somebody looking at functions. A
     * deep export appears under every kind it carries, and that is right: the
     * objects a function is typed against are genuinely in it.
     */
    fun templates(holding: ComponentKind? = null): List<ComponentTemplateView> =
        templates.findAllByOrderByNameAsc()
            .map(::view)
            /*
             * Offered where it was made from: an agent page lists templates of
             * agents, not every template with an agent somewhere inside - a
             * workflow template carries its agent too. A file that does not say
             * what it was made from is offered wherever its kinds are.
             */
            .filter { holding == null || (!it.ofModels && !it.ofIntegrations && holding in it.rootKinds.ifEmpty { it.kinds }) }

    fun template(id: Long): ComponentTemplateView? = templates.findByIdOrNull(id)?.let(::view)

    /** The stored file itself, for the download on the template's own page. */
    fun envelopeOf(id: Long): String = held(id).envelope

    /**
     * Publishes one, refusing a file this installation cannot read.
     *
     * The name is checked case-insensitively against the ones already here.
     * Unlike an *import*, this does not rename around a collision and must not:
     * a rename is right when a file is being unpacked into somebody's workspace
     * and wrong when somebody is typing a name into a box, where the only honest
     * answer is to say the name is taken and let them pick another.
     */
    @Transactional
    fun create(name: String, description: String?, envelope: String): ComponentTemplateView {
        val cleanName = cleanName(name)
        // Before the row, so a file that cannot be read never becomes a template
        // somebody finds out about by clicking Use on it. A models file was
        // checked by whoever wrote it, which is this installation.
        // An integrations file is read whole by its own reader, which refuses what this installation cannot hold.
        if (ofIntegrations(envelope)) readIntegrationsFile(mapper, envelope) else if (!ofModels(envelope)) importer.describe(envelope)
        if (templates.findByNameIgnoreCase(cleanName) != null) throw TemplateNameTakenException(cleanName)

        val now = OffsetDateTime.now()
        val who = currentUser()
        return view(
            templates.save(
                ComponentTemplate(
                    name = cleanName,
                    description = description?.trim()?.ifEmpty { null },
                    envelope = envelope,
                    createdAt = now,
                    createdBy = who,
                    lastModifiedAt = now,
                    lastModifiedBy = who,
                ),
            ),
        )
    }

    /**
     * Renames, rewords, or replaces the file.
     *
     * Replacing is how a template is brought up to date, and it is the whole of
     * how: nothing here follows the components it was taken from, so a function
     * improved after the template was published is in the template only when
     * somebody puts it there. A null [envelope] leaves the stored one alone, so
     * fixing a typo in a description cannot quietly rewrite what is inside.
     */
    @Transactional
    fun update(id: Long, name: String, description: String?, envelope: String?): ComponentTemplateView {
        val held = held(id)
        val cleanName = cleanName(name)
        envelope?.let {
            when {
                ofIntegrations(it) -> readIntegrationsFile(mapper, it)
                !ofModels(it) -> importer.describe(it)
            }
        }
        templates.findByNameIgnoreCase(cleanName)
            ?.takeIf { it.id != held.id }
            ?.let { throw TemplateNameTakenException(cleanName) }

        held.name = cleanName
        held.description = description?.trim()?.ifEmpty { null }
        envelope?.let { held.envelope = it }
        held.lastModifiedAt = OffsetDateTime.now()
        held.lastModifiedBy = currentUser()
        return view(templates.save(held))
    }

    @Transactional
    fun delete(id: Long): String? {
        val held = templates.findByIdOrNull(id) ?: return null
        templates.delete(held)
        // The name, so the caller can say what it was in the audit entry. What it
        // created in the workspaces that used it stays where it is: a template is
        // a copy, and deleting the copy somebody took a copy from removes nothing.
        return held.name
    }

    private fun held(id: Long): ComponentTemplate =
        templates.findByIdOrNull(id) ?: throw TemplateNotFoundException(id)

    private fun cleanName(name: String): String {
        val clean = name.trim()
        if (clean.isEmpty()) throw TemplateNameInvalidException("A template needs a name")
        if (clean.length > 120) throw TemplateNameInvalidException("A template's name has to be 120 characters or less")
        return clean
    }

    /**
     * A row, plus whatever its envelope says about itself.
     *
     * Parsed on every read rather than copied into columns beside the file. It is
     * a small parse of a small document on an administrator's list, and what it
     * buys is that the page cannot describe a template as holding something the
     * file does not — which is exactly what a stored summary would eventually do
     * the first time one of these is replaced.
     */
    /** A template of models: what is in it is the models, named under their providers. */
    private fun modelsView(held: ComponentTemplate): ComponentTemplateView {
        val root = mapper.readTree(held.envelope)
        val models = root.path("modelProviders").values().flatMap { provider ->
            val by = provider.path("name").asString("")
            provider.path("models").values().map { "${it.path("name").asString("")} ($by)" }
        }
        return ComponentTemplateView(
            id = held.id!!,
            name = held.name,
            description = held.description,
            formatVersion = root.path("formatVersion").asInt(0),
            producedBy = root.path("producedBy").asString(null),
            depth = null,
            kinds = emptyList(),
            componentCount = models.size,
            contents = emptyList(),
            usable = true,
            problem = null,
            createdAt = held.createdAt,
            createdBy = held.createdBy,
            lastModifiedAt = held.lastModifiedAt,
            lastModifiedBy = held.lastModifiedBy,
            ofModels = true,
            models = models,
        )
    }

    /** A template of connections and MCP servers: what is in it is their names. */
    private fun integrationsView(held: ComponentTemplate): ComponentTemplateView {
        val read = runCatching { readIntegrationsFile(mapper, held.envelope) }
        val file = read.getOrNull()
        val root = runCatching { mapper.readTree(held.envelope) }.getOrNull()
        return ComponentTemplateView(
            id = held.id!!,
            name = held.name,
            description = held.description,
            formatVersion = root?.path("formatVersion")?.asInt(0),
            producedBy = root?.path("producedBy")?.asString(null),
            depth = null,
            kinds = emptyList(),
            componentCount = (file?.connections?.size ?: 0) + (file?.mcpServers?.size ?: 0),
            contents = emptyList(),
            // Read whole, so a file from a newer Orknux is still listed and says why it cannot be used.
            usable = file != null,
            problem = read.exceptionOrNull()?.message,
            createdAt = held.createdAt,
            createdBy = held.createdBy,
            lastModifiedAt = held.lastModifiedAt,
            lastModifiedBy = held.lastModifiedBy,
            ofIntegrations = true,
            connections = file?.connections.orEmpty().map { it.path("name").asString("") },
            mcpServers = file?.mcpServers.orEmpty().map { it.path("name").asString("") },
        )
    }

    private fun view(held: ComponentTemplate): ComponentTemplateView {
        if (ofModels(held.envelope)) return modelsView(held)
        if (ofIntegrations(held.envelope)) return integrationsView(held)
        val summary = runCatching { importer.describe(held.envelope) }
        val read = summary.getOrNull()
        return ComponentTemplateView(
            id = held.id!!,
            name = held.name,
            description = held.description,
            formatVersion = read?.formatVersion,
            producedBy = read?.producedBy,
            depth = read?.depth,
            kinds = read?.kinds.orEmpty(),
            rootKinds = read?.roots.orEmpty(),
            componentCount = read?.componentCount ?: 0,
            contents = read?.names.orEmpty().map { (kind, name) -> TemplateComponentView(kind, name) },
            usable = read != null,
            // The refusal in the words the format itself uses — this is where an
            // envelope from a version this installation no longer reads says so,
            // on the row, instead of throwing out of a button somebody pressed.
            problem = summary.exceptionOrNull()?.message,
            createdAt = held.createdAt,
            createdBy = held.createdBy,
            lastModifiedAt = held.lastModifiedAt,
            lastModifiedBy = held.lastModifiedBy,
        )
    }

    private fun currentUser(): String = SecurityContextHolder.getContext().authentication?.name ?: "system"
}

/** One thing inside a template, as the file names it. */
data class TemplateComponentView(val kind: ComponentKind, val name: String)

/**
 * A template as a page shows it: the row, and what reading its envelope found.
 *
 * Everything below [description] is derived from the stored file and nothing is
 * a column. [usable] is false only for a template whose envelope this
 * installation can no longer read — a rollback past the format version it was
 * written under — and [problem] then carries the format's own refusal, which is
 * a sentence naming both versions rather than a stack trace.
 */
data class ComponentTemplateView(
    val id: Long,
    val name: String,
    val description: String?,
    /** Null when the envelope could not be read at all. */
    val formatVersion: Int?,
    val producedBy: String?,
    val depth: ExportDepth?,
    /** Which kinds are inside, each once — what the list shows as a summary. */
    val kinds: List<ComponentKind>,
    /** What it was made from, by kind - which page offers it. */
    val rootKinds: List<ComponentKind> = emptyList(),
    /** A template of models rather than components; see [ComponentTemplateService.modelTemplates]. */
    val ofModels: Boolean = false,
    /** The models in it, as "name (provider)", for a template of models. */
    val models: List<String> = emptyList(),
    /** A template of connections and MCP servers; see [ComponentTemplateService.integrationTemplates]. */
    val ofIntegrations: Boolean = false,
    /** The connections in it, by name, for a template of integrations. */
    val connections: List<String> = emptyList(),
    /** The MCP servers in it, by name, for a template of integrations. */
    val mcpServers: List<String> = emptyList(),
    val componentCount: Int,
    val contents: List<TemplateComponentView>,
    /** False when this installation cannot read the stored envelope. */
    val usable: Boolean,
    /** Why not, in words, when [usable] is false. */
    val problem: String?,
    val createdAt: OffsetDateTime,
    val createdBy: String,
    val lastModifiedAt: OffsetDateTime,
    val lastModifiedBy: String,
)

class TemplateNotFoundException(id: Long) : RuntimeException("There is no template with id $id")

class TemplateNameTakenException(name: String) :
    RuntimeException("This installation already has a template called $name")

class TemplateNameInvalidException(says: String) : RuntimeException(says)
