package io.mszymanski.orknux.server.plugin

import io.mszymanski.orknux.server.obj.references
import io.mszymanski.orknux.server.obj.ObjectProperty
import io.mszymanski.orknux.server.obj.PropertyKind
import io.mszymanski.orknux.server.obj.WorkflowObject
import io.mszymanski.orknux.server.obj.WorkflowObjectRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * Turns the shapes a plugin exports into objects a workspace can point at.
 *
 * The sibling of [PluginFunctionRegistry], and the same arrangement: the
 * declaration is the plugin's, these rows are the server's record of it, and
 * they are scoped to the plugin rather than to a workspace so every workspace
 * can use them.
 *
 * **Rows in the table a workspace's own objects live in**, deliberately. A
 * property points at an object by id, and everything that follows one of those
 * references — the shape a model is told, the check an answer is held to, the
 * picker a return type is chosen from — follows it without asking whose row it
 * is. A table of its own would mean teaching every one of those about a second
 * kind, and every one of them would eventually be taught late.
 *
 * **Two passes, because a shape may point at another.** The plugin names its
 * references — `of: 'User'` — and a reference on a row is an id, so nothing can
 * be wired until every row exists. The first pass makes or updates the rows
 * without their references; the second fills them in. A plugin whose objects
 * point at each other in a circle is fine: the rows exist by then.
 *
 * Reconciled rather than appended, again like the functions: what the plugin
 * declares now is what exists afterwards. One it no longer declares goes —
 * unless something still points at it, which is the plugin taking away
 * something in use, and worth refusing rather than breaking.
 */
@Service
class PluginObjectRegistry(
    private val objects: WorkflowObjectRepository,
    private val declarations: PluginDeclarations,
) {

    /**
     * Makes the plugin's declarations the set of objects it exports.
     *
     * @return the names now exported, for the audit line and the response.
     */
    @Transactional
    fun reconcile(plugin: Plugin): List<String> {
        val pluginId = requireNotNull(plugin.id)
        val declared = declarations.readObjects(plugin.declaredObjects)
        val wanted = declared.associateBy { qualified(plugin.key, it.name) }

        val existing = objects.findByPluginId(pluginId).associateBy { it.name }

        /*
         * Gone from the declaration means gone — unless something points at
         * it. A property whose reference was deleted out from under it is a
         * shape that silently stops describing anything, which is the kind of
         * breakage nobody notices until a run fails.
         */
        val removed = existing.keys - wanted.keys
        removed.forEach { name ->
            val users = pointingAt(requireNotNull(existing.getValue(name).id), except = pluginId)
            if (users.isNotEmpty()) throw PluginObjectInUseException(name, users)
        }
        objects.deleteAll(removed.map(existing::getValue))

        /*
         * First pass: every row exists, with its scalar fields settled and its
         * references left empty. Nothing can point at anything yet, because
         * the thing it points at may be three declarations further down.
         */
        val rows = wanted.mapValues { (name, declaration) ->
            val row = existing[name]?.apply {
                this.description = declaration.description
                this.lastModifiedAt = OffsetDateTime.now()
                this.lastModifiedBy = "plugin ${plugin.key}"
            } ?: WorkflowObject(
                workspaceId = null,
                pluginId = pluginId,
                name = name,
                description = declaration.description,
                createdBy = "plugin ${plugin.key}",
                lastModifiedAt = OffsetDateTime.now(),
                lastModifiedBy = "plugin ${plugin.key}",
            )
            objects.save(row)
        }

        // Second pass: the references, now that there is something to point at.
        rows.forEach { (name, row) ->
            val declaration = wanted.getValue(name)
            row.properties = declaration.properties.map { property ->
                val kind = PropertyKind.valueOf(property.kind)
                ObjectProperty(
                    name = property.name,
                    kind = kind,
                    // The plugin's own spelling, resolved against its own set.
                    refObjectId = property.of?.let { rows[qualified(plugin.key, it)]?.id },
                    elementKind = property.elementKind?.let { PropertyKind.valueOf(it) },
                    description = property.description,
                )
            }.toMutableList()
            objects.save(row)
        }

        return wanted.keys.sorted()
    }

    /**
     * Whether anything would break if this plugin were unloaded.
     *
     * Asked before, not during: unloading cascades these rows away, and a
     * property pointing at one that no longer exists describes nothing.
     */
    fun inUse(plugin: Plugin): Map<String, List<String>> {
        val pluginId = requireNotNull(plugin.id)
        return objects.findByPluginId(pluginId)
            .associate { it.name to pointingAt(requireNotNull(it.id), except = pluginId) }
            .filterValues { it.isNotEmpty() }
    }

    /**
     * The objects that point at this one, by name.
     *
     * Only the ones outside the plugin: the plugin's own properties pointing
     * at the plugin's own objects go and come back together, so counting them
     * would make every reconcile of a plugin with a nested shape a refusal.
     *
     * A workflow node that names an object by id is not looked for here.
     * Those are nulled on delete by the schema rather than refused, which is
     * the existing rule for a workspace's own object and not one to change
     * from underneath a plugin.
     */
    private fun pointingAt(objectId: Long, except: Long): List<String> = objects.findAll()
        .filter { it.pluginId != except }
        .filter { held -> held.properties.any { objectId in it.references() } }
        .map { it.name }
        .sorted()

    /**
     * `jira_Issue` — the plugin's key, then the name it declared.
     *
     * Prefixed for the reason a function is: an object is pointed at by name
     * on a screen, and two plugins may reasonably both export `User`. The
     * key is unique and a plugin's own names are unique within it, so the
     * pair cannot collide with anything — including with a workspace's own,
     * which is the collision that would otherwise be confusing rather than
     * merely broken.
     */
    private fun qualified(key: String, name: String): String = "${key}_$name"
}

/** Unloading would take shapes away that something outside the plugin points at. */
class PluginObjectsInUseException(used: Map<String, List<String>>) : RuntimeException(
    "This plugin exports shapes that are still pointed at: " +
        used.entries.joinToString("; ") { (name, users) -> "$name (${users.joinToString(", ")})" } +
        ". Change those first.",
)

class PluginObjectInUseException(name: String, users: List<String>) : RuntimeException(
    "The plugin no longer exports \"$name\", but ${users.joinToString(", ")} still points at it. " +
        "Change those first.",
)
