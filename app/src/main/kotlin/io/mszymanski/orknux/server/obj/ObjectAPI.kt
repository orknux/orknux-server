package io.mszymanski.orknux.server.obj

import io.mszymanski.orknux.server.dependency.ComponentDependants
import io.mszymanski.orknux.server.dependency.DependencyKind
import io.mszymanski.orknux.server.dependency.phrases
import io.mszymanski.orknux.server.graphql.Refusal
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.server.workspace.pageRequest
import io.mszymanski.orknux.server.workspace.sortBy
import org.springframework.data.domain.Page
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * A workspace's objects: the shapes its workflows pass around.
 *
 * The catalogue rule applies as it does to triggers, actions and conditions —
 * defined once here, pointed at from everywhere else. What differs is that an
 * object describes data rather than doing anything, so nothing instances one;
 * things simply say they are shaped like it.
 */
@Controller
class ObjectAPI(
    private val objects: WorkflowObjectRepository,
    private val workspaces: WorkspaceRepository,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
    private val dependants: ComponentDependants,
    /** How deep a shape may go: Admin > Settings. */
    private val settings: io.mszymanski.orknux.server.attachment.InstallationSettings,
) {

    /**
     * The columns this list can be put in the order of. Issue #358.
     *
     * Source is which of the two owners a shape has - a workspace drew it, or a
     * plugin exports it - so ordering by it groups the workspace's own together,
     * which is what somebody pressing that heading is asking for. The field
     * count is the size of a collection and has no column of its own to order
     * by, so it stays a heading.
     */
    private val OBJECT_ORDERS = mapOf(
        "NAME" to listOf("name"),
        "DESCRIPTION" to listOf("description", "name"),
        "SOURCE" to listOf("pluginId", "name"),
        "LAST_MODIFIED" to listOf("lastModifiedAt"),
    )

    @QueryMapping
    /** @param search what to look for in this list, or null for all of it. */
    fun workspaceObjects(
        @Argument workspaceId: Long,
        @Argument page: Int?,
        @Argument size: Int?,
        @Argument search: String?,
        @Argument order: String?,
        @Argument ascending: Boolean?,
    ): ObjectPage {
        requireWorkspaceAccess(workspaceId)
        val paged = pageRequest(page, size, sortBy(order, ascending, OBJECT_ORDERS, "NAME"))
        val looking = search?.trim().orEmpty()

        return ObjectPage(
            if (looking.isEmpty()) {
                objects.findByWorkspaceId(workspaceId, paged)
            } else {
                objects.searching(workspaceId, looking, paged)
            },
            ::describe,
        )
    }

    @QueryMapping
    fun workflowObject(@Argument id: Long): ObjectView? {
        val held = objects.findByIdOrNull(id)?.takeIf { visible(it) } ?: return null
        return describe(held)
    }

    /**
     * The shapes the loaded plugins export, for a picker that offers them.
     *
     * Beside `workspaceObjects` rather than folded into it: the workspace's
     * page is what somebody draws and edits, and a plugin's shape is neither —
     * it is replaced the next time the plugin is loaded. What they share is
     * the thing that matters to a reference: an id to point at.
     *
     * Installation-wide, like the plugins themselves, and readable by anybody
     * who may see a workspace — pointing at one is the decision, and a picker
     * that cannot show what is on offer cannot be used to make it.
     */
    @QueryMapping
    fun pluginObjects(): List<ObjectView> =
        objects.findByPluginIdIsNotNull().sortedBy { it.name }.map(::describe)

    @MutationMapping
    @Transactional
    fun createObject(@Argument input: CreateObjectInput): ObjectView {
        requireWorkspaceAccess(input.workspaceId)
        val name = input.name.trim()
        requireUsableName(name)
        if (objects.findByWorkspaceIdAndName(input.workspaceId, name) != null) throw ObjectNameTakenException(name)

        val saved = objects.save(
            WorkflowObject(
                workspaceId = input.workspaceId,
                name = name,
                description = input.description?.trim()?.ifEmpty { null },
                properties = propertiesOf(input.workspaceId, input.properties.orEmpty()),
                createdBy = currentUser(),
                lastModifiedBy = currentUser(),
            ),
        )

        auditRecorder.record(input.workspaceId, WorkspaceAuditCategory.OBJECT, "Object $name created")
        return describe(saved)
    }

    /**
     * An object read off a JSON Schema somebody pasted: what a schema says that
     * a property here can hold becomes the object's properties, an object
     * inside it a shape defined in place. See [JsonSchemaObjects] for what is
     * kept and what is left behind.
     */
    @MutationMapping
    @Transactional
    fun createObjectFromSchema(
        @Argument workspaceId: Long,
        @Argument name: String,
        @Argument description: String?,
        @Argument schema: String,
    ): ObjectView {
        requireWorkspaceAccess(workspaceId)
        val called = name.trim()
        requireUsableName(called)
        if (objects.findByWorkspaceIdAndName(workspaceId, called) != null) throw ObjectNameTakenException(called)

        val read = JsonSchemaObjects.read(schema, settings.objectMaxDepth())
        val saved = objects.save(
            WorkflowObject(
                workspaceId = workspaceId,
                name = called,
                description = description?.trim()?.ifEmpty { null },
                properties = propertiesOf(workspaceId, read.map { it.asInput() }),
                createdBy = currentUser(),
                lastModifiedBy = currentUser(),
            ),
        )
        auditRecorder.record(workspaceId, WorkspaceAuditCategory.OBJECT, "Object $called created from a JSON Schema")
        return describe(saved)
    }

    /** What the schema reader found, as the editor would have sent it, so one path checks and stores both. */
    private fun InlineProperty.asInput(): ObjectPropertyInput = ObjectPropertyInput(
        name = name,
        kind = kind,
        refObjectId = refObjectId,
        elementKind = elementKind,
        description = description,
        properties = properties?.map { it.asInput() },
    )

    /** Backs the editor: the properties on the left, the details on the right. */
    @MutationMapping
    @Transactional
    fun updateObject(@Argument id: Long, @Argument input: UpdateObjectInput): ObjectView {
        val held = objects.findByIdOrNull(id)?.takeIf { visible(it) } ?: throw ObjectNotFoundException(id)
        val workspaceId = editable(held)

        val previousName = held.name
        input.name?.trim()?.let { name ->
            requireUsableName(name)
            if (name != held.name && objects.findByWorkspaceIdAndName(workspaceId, name) != null) {
                throw ObjectNameTakenException(name)
            }
            held.name = name
        }
        input.description?.let { held.description = it.trim().ifEmpty { null } }
        input.properties?.let { held.properties = propertiesOf(workspaceId, it, self = id) }
        held.lastModifiedAt = OffsetDateTime.now()
        held.lastModifiedBy = currentUser()

        val message = if (previousName == held.name) {
            "Object ${held.name} updated"
        } else {
            "Object $previousName renamed to ${held.name}"
        }
        auditRecorder.record(held.workspaceId, WorkspaceAuditCategory.OBJECT, message)
        return describe(held)
    }

    /**
     * An object another object or a webhook depends on is not one to delete.
     *
     * Two of the references to an object are guarded and the rest are not, and
     * that is a decision rather than an omission. An object is a *description of
     * a shape*, and most things naming one are reading the description:
     *
     * - Another object's property. Guarded, and always was: a property with a
     *   dangling reference cannot be shown, checked or offered — there is no
     *   degraded version of it to fall back to.
     * - A webhook's shape. Guarded here. Unlike the rest, this one is not read
     *   by a person — it is what every arriving request is checked against, and
     *   [WebhookAPI][io.mszymanski.orknux.server.trigger.WebhookAPI] answers 404
     *   when it resolves to nothing. A 404 is exactly what a path nobody listens
     *   on returns, so the webhook does not look broken to its caller; it looks
     *   absent, and the only record is a line in the firing log. `TriggerAPI`
     *   already refuses to *save* a webhook whose shape is missing, so deleting
     *   the object was the one door that could put a trigger into a state the
     *   trigger door itself would not accept.
     * - A function's parameter or return object, a tool's parameter, an object
     *   node on a canvas. **Not** guarded, deliberately. Each of these is an
     *   annotation: the shape tells the editor what to write above the code and
     *   what fields the picker downstream can offer. When it goes, the signature
     *   falls back to saying `object` and the picker shows nothing chosen — a
     *   thing somebody sees, on the screen where they would fix it, before
     *   anything runs. None of them reaches a published copy either: a
     *   snapshot's nodes carry `agentId`, `actionId` and `conditionId` and no
     *   object id at all, so there is no frozen copy going on naming one.
     *
     * The line, then, is not "id or name" and not "draft or published": it is
     * whether the reference is read by a person who can act on it, or consulted
     * by something that will quietly turn a caller away.
     */
    @MutationMapping
    @Transactional
    fun deleteObject(@Argument id: Long): Boolean {
        val held = objects.findByIdOrNull(id)?.takeIf { visible(it) } ?: return false
        editable(held)

        val users = dependants.of(DependencyKind.OBJECT, id)
        if (users.isNotEmpty()) throw ObjectInUseException(held.name, users.phrases())

        objects.delete(held)
        auditRecorder.record(held.workspaceId, WorkspaceAuditCategory.OBJECT, "Object ${held.name} deleted")
        return true
    }

    /**
     * The editor's Validate. It answers rather than throws: a half-written
     * property is what the button is for, not a failed request.
     */
    @MutationMapping
    fun validateObject(@Argument workspaceId: Long, @Argument properties: List<ObjectPropertyInput>): ObjectValidationView {
        requireWorkspaceAccess(workspaceId)

        val seen = mutableSetOf<String>()
        properties.forEachIndexed { index, property ->
            val name = property.name.trim()
            property.properties?.let { nested ->
                inlineProblem(workspaceId, nested, depth = 1)?.let { return ObjectValidationView(valid = false, message = "$name: $it") }
            }
            val says = when {
                name.isEmpty() -> "Property ${index + 1} has no name"
                !NAME.matches(name) -> "$name is not a usable property name"
                !seen.add(name) -> "$name is named twice"
                property.kind == PropertyKind.OBJECT && property.refObjectId == null && property.properties == null ->
                    "$name says it is an object but does not say which"
                property.kind == PropertyKind.ARRAY && property.refObjectId == null && property.elementKind == null && property.properties == null ->
                    "$name is an array but does not say of what"
                property.refObjectId != null && objects.findByIdOrNull(property.refObjectId)?.workspaceId != workspaceId ->
                    "$name points at an object this workspace does not have"
                (property.description?.trim()?.length ?: 0) > DESCRIPTION_LIMIT ->
                    "$name has a description longer than $DESCRIPTION_LIMIT characters"
                else -> null
            }
            if (says != null) return ObjectValidationView(valid = false, message = says)
        }
        return ObjectValidationView(valid = true, message = "${properties.size} properties, all resolvable")
    }

    /**
     * What the editor sent, checked as it is turned into properties.
     *
     * Every reference is resolved here rather than trusted: a property naming
     * another workspace's object would let one workspace read the shape of
     * another's data. [self] is allowed through, because an object that refers
     * to itself is how a tree is described.
     */
    private fun propertiesOf(
        workspaceId: Long,
        sent: List<ObjectPropertyInput>,
        self: Long? = null,
    ): MutableList<ObjectProperty> {
        val seen = mutableSetOf<String>()
        return sent.map { property ->
            val name = property.name.trim()
            requireUsableName(name)
            if (!seen.add(name)) throw ObjectPropertyInvalidException("$name is named twice")

            property.refObjectId?.let { referenced ->
                if (referenced != self && objects.findByIdOrNull(referenced)?.workspaceId != workspaceId) {
                    throw ObjectPropertyInvalidException("$name points at an object this workspace does not have")
                }
            }
            if (property.kind == PropertyKind.OBJECT && property.refObjectId == null && property.properties == null) {
                throw ObjectPropertyInvalidException("$name says it is an object but does not say which")
            }
            if (property.kind == PropertyKind.ARRAY && property.refObjectId == null && property.elementKind == null && property.properties == null) {
                throw ObjectPropertyInvalidException("$name is an array but does not say of what")
            }

            val inline = property.properties?.let { inlineOf(workspaceId, it, self, depth = 1) }
            ObjectProperty(
                name = name,
                kind = property.kind,
                refObjectId = property.refObjectId,
                // A list of objects defined here is a list of objects, whatever the row left unsaid.
                elementKind = if (property.kind == PropertyKind.ARRAY && inline != null) PropertyKind.OBJECT else property.elementKind,
                description = describedAs(name, property.description),
                inlineProperties = InlineShapes.write(inline),
            )
        }.toMutableList()
    }

    /**
     * The fields of an object defined in place, checked as the top level is -
     * names, references, depth - and refused the same way. [depth] counts the
     * objects this sits inside; the ceiling is the administrator's.
     */
    private fun inlineOf(workspaceId: Long, sent: List<ObjectPropertyInput>, self: Long?, depth: Int): List<InlineProperty> {
        inlineProblem(workspaceId, sent, depth, self)?.let { throw ObjectPropertyInvalidException(it) }
        return sent.map { property ->
            val nested = property.properties?.let { inlineOf(workspaceId, it, self, depth + 1) }
            InlineProperty(
                name = property.name.trim(),
                kind = property.kind,
                refObjectId = property.refObjectId,
                elementKind = if (property.kind == PropertyKind.ARRAY && nested != null) PropertyKind.OBJECT else property.elementKind,
                description = describedAs(property.name.trim(), property.description),
                properties = nested,
            )
        }
    }

    /** What is wrong with an inline shape, in one sentence, or null where nothing is. The editor's Validate asks; a save throws it. */
    private fun inlineProblem(workspaceId: Long, sent: List<ObjectPropertyInput>, depth: Int, self: Long? = null): String? {
        val deepest = settings.objectMaxDepth()
        if (depth > deepest) return "nests deeper than the $deepest levels this installation allows"
        val seen = mutableSetOf<String>()
        sent.forEachIndexed { index, property ->
            val name = property.name.trim()
            when {
                name.isEmpty() -> return "field ${index + 1} has no name"
                !NAME.matches(name) -> return "$name is not a usable property name"
                !seen.add(name) -> return "$name is named twice"
                property.kind == PropertyKind.OBJECT && property.refObjectId == null && property.properties == null ->
                    return "$name says it is an object but does not say which"
                property.kind == PropertyKind.ARRAY && property.refObjectId == null && property.elementKind == null && property.properties == null ->
                    return "$name is an array but does not say of what"
                property.refObjectId != null && property.refObjectId != self &&
                    objects.findByIdOrNull(property.refObjectId)?.workspaceId != workspaceId ->
                    return "$name points at an object this workspace does not have"
                (property.description?.trim()?.length ?: 0) > DESCRIPTION_LIMIT ->
                    return "$name has a description longer than $DESCRIPTION_LIMIT characters"
            }
            property.properties?.let { nested -> inlineProblem(workspaceId, nested, depth + 1, self)?.let { return "$name: $it" } }
        }
        return null
    }

    /**
     * Whether this row may be read here.
     *
     * A plugin's shape is visible to anybody who may see any workspace: it
     * belongs to the installation, the way the plugin does, and a reference
     * to one can be drawn in every workspace at once.
     */
    private fun visible(held: WorkflowObject): Boolean =
        held.pluginId != null || access.canSee(requireNotNull(held.workspaceId))

    /**
     * The workspace whose shape this is, refusing one that is a plugin's.
     *
     * A plugin's object is replaced wholesale the next time the plugin is
     * loaded, so an edit made here would be an edit somebody loses without
     * being told. Refused with a sentence saying where it comes from rather
     * than reported as missing, because it is plainly there on the screen.
     */
    private fun editable(held: WorkflowObject): Long =
        held.workspaceId ?: throw PluginObjectNotEditableException(held.name)

    private fun describe(held: WorkflowObject): ObjectView {
        /*
         * The names a property could be pointing at: this owner's, and every
         * plugin's. A plugin's shape may point within itself, and a
         * workspace's may point at a plugin's - so a map of only one side
         * would draw `unknown` beside a reference that resolves perfectly.
         */
        val names = (held.workspaceId?.let(objects::findByWorkspaceId).orEmpty() + objects.findByPluginIdIsNotNull())
            .associate { it.id to it.name }
        return ObjectView(
            id = requireNotNull(held.id),
            workspaceId = held.workspaceId,
            pluginId = held.pluginId,
            name = held.name,
            description = held.description,
            properties = held.properties.map { property ->
                ObjectPropertyView(
                    name = property.name,
                    kind = property.kind,
                    refObjectId = property.refObjectId,
                    elementKind = property.elementKind,
                    description = property.description,
                    display = display(property, names),
                    properties = inlineViews(property.inline(), names),
                )
            },
            propertyCount = held.properties.size,
            createdAt = held.createdAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            createdBy = held.createdBy,
            lastModifiedAt = held.lastModifiedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            lastModifiedBy = held.lastModifiedBy,
        )
    }

    /**
     * The type as the editor shows it — `string`, `ApiResponse`,
     * `array<FileObject>`. Worked out here so the screen never has to assemble
     * a type out of three columns, and so a reference reads as the name it has
     * now rather than the one it had when it was pointed at.
     */
    /**
     * The fields of an object a node's own field defines in place, checked as
     * a saved object's are and written as the column holds them. Lent to the
     * graph so a node field and an object property are held to one rule.
     */
    fun inlineFields(workspaceId: Long, sent: List<ObjectPropertyInput>): String? =
        InlineShapes.write(inlineOf(workspaceId, sent, self = null, depth = 1))

    /** And back, as the editor reads them; a reference inside reads as the name it has now. */
    fun inlineFieldViews(json: String?): List<ObjectPropertyView>? {
        val fields = InlineShapes.read(json) ?: return null
        val names = objects.findAllById(InlineShapes.references(fields)).associate { it.id to it.name }
        return inlineViews(fields, names)
    }

    /** An inline shape as the editor reads it back, field by field, as deep as it goes. */
    private fun inlineViews(fields: List<InlineProperty>?, names: Map<Long?, String>): List<ObjectPropertyView>? = fields?.map { field ->
        ObjectPropertyView(
            name = field.name,
            kind = field.kind,
            refObjectId = field.refObjectId,
            elementKind = field.elementKind,
            description = field.description,
            display = display(field.asProperty(), names),
            properties = inlineViews(field.properties, names),
        )
    }

    private fun display(property: ObjectProperty, names: Map<Long?, String>): String = when (property.kind) {
        PropertyKind.OBJECT -> names[property.refObjectId] ?: if (property.inlineProperties != null) "object" else "(deleted object)"
        PropertyKind.ARRAY -> {
            val element = property.refObjectId
                ?.let { names[it] ?: "(deleted object)" }
                ?: property.elementKind?.name?.lowercase()
                ?: "?"
            "array<$element>"
        }
        else -> property.kind.name.lowercase()
    }

    /**
     * A field's description, trimmed, or null where there is nothing to say.
     *
     * Refused rather than cut when it runs past the column. A description cut
     * to fit stops mid-sentence, and a sentence that stops mid-sentence is
     * worse than none: it is read as the whole of what the author meant, by a
     * person and by a model alike.
     */
    private fun describedAs(name: String, said: String?): String? {
        val trimmed = said?.trim()?.ifEmpty { null } ?: return null
        if (trimmed.length > DESCRIPTION_LIMIT) {
            throw ObjectPropertyInvalidException(
                "$name has a description longer than $DESCRIPTION_LIMIT characters",
            )
        }
        return trimmed
    }

    /** A name a reference can point at: "oddly named" cannot. */
    private fun requireUsableName(name: String) {
        if (name.isEmpty()) throw ObjectNameInvalidException()
        if (!NAME.matches(name)) throw ObjectNameInvalidException(name)
    }

    private fun currentUser(): String =
        SecurityContextHolder.getContext().authentication?.name ?: "system"

    private fun requireWorkspaceAccess(workspaceId: Long) {
        access.requireVisible(workspaceId)
    }

    private companion object {
        /** What can be written after a dot, which is how a property is read. */
        val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

        /**
         * How much a field may say about itself.
         *
         * The same bound every other description on this side carries. Prose
         * needs more room than a name, and it does not need an unbounded amount
         * of it: what a field means fits in a sentence or two, and anything
         * longer is documentation that belongs on the object rather than on one
         * of its fields.
         */
        const val DESCRIPTION_LIMIT = 500
    }
}

data class CreateObjectInput(
    val workspaceId: Long,
    val name: String,
    val description: String? = null,
    val properties: List<ObjectPropertyInput>? = null,
)

data class UpdateObjectInput(
    val name: String? = null,
    val description: String? = null,
    /** Null leaves the properties alone; a list replaces them wholesale. */
    val properties: List<ObjectPropertyInput>? = null,
)

data class ObjectPropertyInput(
    val name: String,
    val kind: PropertyKind,
    val refObjectId: Long? = null,
    val elementKind: PropertyKind? = null,
    /** What the field means; blank is the same as saying nothing. */
    val description: String? = null,
    /** The fields of an object defined here, for an OBJECT naming no refObjectId or an ARRAY of such. */
    val properties: List<ObjectPropertyInput>? = null,
)

data class ObjectPropertyView(
    val name: String,
    val kind: PropertyKind,
    val refObjectId: Long?,
    val elementKind: PropertyKind?,
    /** What the field means, as its author wrote it. */
    val description: String?,
    /** Ready to show: `string`, `ApiResponse`, `array<FileObject>`. */
    val display: String,
    /** The fields of an object defined in place; null where it points at a named one or holds a scalar. */
    val properties: List<ObjectPropertyView>? = null,
)

data class ObjectView(
    val id: Long,
    /** Null for a shape a plugin exports: it belongs to the installation. */
    val workspaceId: Long?,
    /** Set for a shape a plugin exports; there is nowhere to edit one. */
    val pluginId: Long? = null,
    val name: String,
    val description: String?,
    val properties: List<ObjectPropertyView>,
    /** What the list's badge shows, so an empty object reads as empty. */
    val propertyCount: Int,
    val createdAt: String,
    val createdBy: String,
    val lastModifiedAt: String,
    val lastModifiedBy: String,
)

/**
 * A plugin's shape is the plugin's, and there is nowhere to edit it.
 *
 * Said as a refusal rather than as absence: the object is on the screen, so
 * "no such object" would read as a bug. What it says instead is who owns it.
 */
class PluginObjectNotEditableException(val name: String) : RuntimeException(
    "$name comes from a plugin. A plugin's shapes are replaced every time it is loaded, so they are " +
        "changed by changing the plugin.",
), io.mszymanski.orknux.server.graphql.Refusal {

    override val arguments get() = mapOf("name" to name)
}

/** What Validate answers; it is a report, not a failure. */
data class ObjectValidationView(val valid: Boolean, val message: String)

data class ObjectPage(
    val content: List<ObjectView>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
) {
    constructor(page: Page<WorkflowObject>, describe: (WorkflowObject) -> ObjectView) : this(
        content = page.content.map(describe),
        page = page.number,
        size = page.size,
        totalElements = page.totalElements.toInt(),
        totalPages = page.totalPages,
    )
}

class ObjectNotFoundException(val id: Long) : RuntimeException("No object with id $id"), Refusal {

    override val arguments get() = mapOf("id" to id)
}

class ObjectNameTakenException(val name: String) :
    RuntimeException("This workspace already has an object called $name"), Refusal {

    override val arguments get() = mapOf("name" to name)
}

class ObjectNameInvalidException(name: String? = null) : RuntimeException(
    if (name == null) {
        "An object needs a name"
    } else {
        "$name cannot be used as a name: it has to start with a letter or underscore and hold only letters, digits and underscores"
    },
)

class ObjectPropertyInvalidException(val says: String) : RuntimeException(says), Refusal {

    override val arguments get() = mapOf("says" to says)
}

class ObjectInUseException(val name: String, val users: List<String>) : RuntimeException(
    "$name is used by ${users.joinToString(", ")}, so it cannot be deleted",
), Refusal {

    override val arguments get() = mapOf("name" to name, "users" to users)
}

