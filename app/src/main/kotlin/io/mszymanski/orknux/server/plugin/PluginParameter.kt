package io.mszymanski.orknux.server.plugin

import io.mszymanski.orknux.connector.security.SECRET_COLUMN_LENGTH
import io.mszymanski.orknux.connector.security.SecretConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime

/**
 * What one workspace set one of a plugin's parameters to.
 *
 * A plugin is loaded once for the whole installation, but what it should be
 * pointed at is not the same everywhere: two workspaces using the same issue
 * tracker plugin talk to two projects with two tokens. So the declaration belongs
 * to the plugin and the answer belongs to the workspace, and this row is the
 * answer.
 *
 * Exactly one of [literalValue], [secretValue] and [variableId] is set.
 *
 * A literal is a value somebody typed and is stored as typed, in the clear,
 * because it is shown back to them. A secret somebody typed goes to
 * [secretValue] instead, which is encrypted and never shown back - the screen
 * is told that it is set and nothing more. And [variableId] points at one of
 * the workspace's variables, which is still the better answer wherever one
 * credential serves more than one thing: it is shared, owned, has a history,
 * and rotating it is one edit rather than four.
 *
 * A row for a parameter the plugin no longer declares is possible - a plugin can be
 * loaded again with a different declaration - and it is simply not read. Deleting
 * it on reload would throw away a value that a corrected plugin would want back an
 * hour later.
 */
@Entity
@Table(name = "plugin_parameter")
class PluginParameterSetting(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "plugin_id", nullable = false)
    val pluginId: Long,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    /** The name the plugin declared. Not a foreign key: the declaration is JSON. */
    @Column(nullable = false, length = 64)
    val name: String,

    /** What somebody typed, or null when this points at a variable. */
    @Column(name = "literal_value", columnDefinition = "text")
    var literalValue: String? = null,

    /**
     * The same thing for a parameter the plugin declares secret, kept where a
     * secret belongs.
     *
     * Its own column rather than [literalValue] because the two are stored
     * differently: this one goes through [SecretConverter], so what sits in
     * the database is ciphertext and what leaves this server is nothing at
     * all - the screen is told *that* a value is set and never what it is.
     *
     * Pointing at a workspace variable is still the better answer wherever one
     * token serves more than one thing: a variable is shared, owned, and has a
     * history, and rotating it is one edit rather than four. This is for the
     * other case - one plugin, one credential - where making a variable to
     * hold it was a step that bought nothing.
     */
    @Convert(converter = SecretConverter::class)
    @Column(name = "secret_value", length = SECRET_COLUMN_LENGTH)
    var secretValue: String? = null,

    /** Which of the workspace's variables this reads, or null when it was typed in. */
    @Column(name = "variable_id")
    var variableId: Long? = null,

    @Column(name = "last_modified_at", nullable = false)
    var lastModifiedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "last_modified_by", nullable = false, length = 120)
    var lastModifiedBy: String = "",
)

interface PluginParameterSettingRepository : JpaRepository<PluginParameterSetting, Long> {

    fun findByPluginIdAndWorkspaceId(pluginId: Long, workspaceId: Long): List<PluginParameterSetting>

    /** Every answer one workspace gave, to every plugin: what a workspace copy carries. */
    fun findByWorkspaceId(workspaceId: Long): List<PluginParameterSetting>

    fun findByPluginIdAndWorkspaceIdAndName(pluginId: Long, workspaceId: Long, name: String): PluginParameterSetting?
}

/**
 * One of a plugin's parameters as one workspace sees it: what the plugin asked
 * for, and what this workspace answered.
 *
 * [literal] carries a typed-in value back to the form so it can be edited.
 * [variableName] carries only the name of the variable a reference points at -
 * never what it holds. A screen that could read a workspace's secrets by asking
 * a plugin screen for them would be a way around the variables screen, which
 * makes revealing one an audited act.
 */
data class PluginParameterSettingView(
    val name: String,
    val description: String?,
    val type: String,
    /** Which kind of connection a `connection` parameter takes; null for every other type. */
    val connectionType: String?,
    val required: Boolean,
    val secret: Boolean,
    /**
     * The values this may take, where the plugin knows them all.
     *
     * Empty where anything typed will do. Carried on the answered view as well
     * as on the declaration because this is the one the settings form reads -
     * a picker is drawn from what a parameter may be, and the form never sees
     * the declaration.
     */
    val options: List<String> = emptyList(),
    val literal: String?,
    /**
     * Whether a secret has been typed in here, which is all this says.
     *
     * Never the value. The point of keeping a typed secret in an encrypted
     * column is that nothing carries it back out - not to this screen, not to
     * a screenshot of it, and not to whoever is reading over a shoulder. What
     * a person needs from the page is whether the thing is answered, and that
     * is one boolean.
     */
    val secretSet: Boolean = false,
    val variableId: String?,
    val variableName: String?,
    /** Required, and nothing usable is set for it. What the red mark is drawn from. */
    val missing: Boolean,
)

/**
 * A plugin as one workspace sees it.
 *
 * [missing] is the same answer as the marks on the parameters, gathered up: the
 * list needs one boolean per plugin and the detail needs one per parameter, and
 * they must not be able to disagree, so they are computed together.
 */
data class WorkspacePluginView(
    val plugin: PluginView,
    val parameters: List<PluginParameterSettingView>,
    val missing: List<String>,
)

class PluginParameterUnknownException(name: String, key: String) : RuntimeException(
    "\"$name\" is not a parameter the $key plugin declares. A plugin is given what it asked for and " +
        "nothing else - that is the point of it declaring them.",
)

class PluginParameterAmbiguousException(name: String) : RuntimeException(
    "\"$name\" was given both a value and a variable to read. It is one or the other.",
)

class PluginParameterEmptyException(name: String) : RuntimeException(
    "\"$name\" was given neither a value nor a variable. Clear it instead if that is what you meant.",
)

/**
 * Kept for the callers that still catch it, and no longer thrown.
 *
 * A secret typed into a parameter is stored encrypted now and never shown
 * back, so the sentence this carried - "a value typed here is stored as typed
 * and shown back on this page" - stopped being true of secrets. Left in place
 * rather than deleted because an installation upgrading past this may still
 * have it recorded in an audit line somebody reads.
 */
@Deprecated("A secret may be typed in now; it is stored encrypted and never returned.")
class PluginParameterNotSecretException(name: String) : RuntimeException(
    "The plugin declares \"$name\" as a secret, so it cannot be typed in here. Keep it as a variable and " +
        "point this at that - a value typed here is stored as typed and shown back on this page.",
)

class PluginParameterNotValueException(name: String, type: String, given: String) : RuntimeException(
    "\"$name\" is a $type, and \"$given\" is not one.",
)

class PluginParameterVariableElsewhereException(name: String) : RuntimeException(
    "That variable belongs to another workspace, so \"$name\" cannot read it.",
)
