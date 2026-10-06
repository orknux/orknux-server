package io.mszymanski.orknux.server.plugin

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.OffsetDateTime

/**
 * A plugin loaded into this installation.
 *
 * Nothing here says what a plugin *does*. It is the text somebody uploaded, what
 * it is called, and who put it there — the record of a thing that has been
 * loaded, not a description of an interface it implements. Entry points,
 * capabilities and grants all belong to work that has not been done yet, and
 * inventing columns for them now would be guessing.
 *
 * Installation-level, so there is no workspace on the row. An operator loads a
 * plugin once; which workspaces may use it is a later question.
 */
@Entity
@Table(name = "plugin")
class Plugin(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    /**
     * What the plugin calls itself, and the namespace for what it declares.
     *
     * The plugin's identity rather than the file's: loading the same key again
     * replaces what is loaded, whatever the file was named. Its functions arrive as
     * `key_name`, which is why it is short.
     */
    @Column(name = "plugin_key", nullable = false, length = 32)
    var key: String,

    /** From the filename. What it is called on screen; the key is what it *is*. */
    @Column(nullable = false, length = 200)
    var name: String,

    @Column(nullable = false, length = 255)
    var filename: String,

    @Column(nullable = false, columnDefinition = "text")
    var source: String,

    /**
     * What it was written in, or null when it was written in JavaScript.
     *
     * Never evaluated — [source] is what runs, always. This is kept so the plugin can
     * be downloaded as the thing somebody actually wrote: hand back the compiled
     * output instead and the annotations are gone, with no way to recover them.
     */
    @Column(columnDefinition = "text")
    var typescript: String? = null,

    @Column(name = "size_bytes", nullable = false)
    var sizeBytes: Long,

    /**
     * The plugin API this plugin says it uses, as it answered when it was
     * uploaded. Only versions this server knows are ever stored.
     */
    @Column(name = "api_version", nullable = false)
    var apiVersion: Int,

    /** Of the source text, so an unchanged re-upload is recognisable. */
    @Column(nullable = false, length = 64)
    var sha256: String,

    /**
     * What the plugin answered when asked which functions it offers, as JSON.
     *
     * The declaration, not the registration: these become callable when they are
     * materialised as `workflow_function` rows, and that is a separate step
     * because a function belongs to a workspace and a plugin does not.
     */
    @Column(name = "declared_functions", nullable = false, columnDefinition = "text")
    var declaredFunctions: String = "[]",

    /**
     * What the plugin answered when asked which tools it offers to agents, as
     * JSON.
     *
     * A surface of its own, not a flag on the functions: workflows call
     * functions and agents call tools, and a plugin says which of what it
     * holds belongs to which reader. A tool proxying one of the functions
     * carries the function's name under `proxyOf`.
     */
    @Column(name = "declared_tools", nullable = false, columnDefinition = "text")
    var declaredTools: String = "[]",

    /**
     * The instruction sets the plugin brings, as JSON.
     *
     * A third surface with a third reader. Nothing here runs: each is markdown
     * an agent reads before doing something, and it ships with the plugin so
     * the knowledge of how the work is meant to be done travels with the code
     * that does it. They reach an agent as a catalog named after the plugin,
     * which is granted like any other — nothing is automatic.
     *
     * Replaced wholesale on every load, the way the functions are. An edit
     * somebody made here would be lost on the next version, so there is
     * nowhere to make one: a plugin's skill is the plugin's.
     */
    @Column(name = "declared_skills", nullable = false, columnDefinition = "text")
    var declaredSkills: String = "[]",

    /**
     * The connection kinds it declares, as JSON, so a workspace can hold hosts
     * of each by name. See [PluginConnectionTypeView]. Issue #363.
     */
    @Column(name = "declared_connection_types", nullable = false, columnDefinition = "text")
    var declaredConnectionTypes: String = "[]",

    /**
     * The workflow actions it declares, as JSON, so the editor's Action node
     * can offer them and a run can call one. See [PluginActionView]. Issue #438.
     *
     * A row rather than a question to the sandbox, for the reason every other
     * declaration is: the editor lists them, the validator checks a node's
     * wiring against them and the runner reads a run's inputs off them, and
     * none of those should have to load a plugin to find out what it says.
     */
    @Column(name = "declared_actions", nullable = false, columnDefinition = "text")
    var declaredActions: String = "[]",

    /**
     * The shapes the plugin exports, as JSON.
     *
     * The declaration, not the registration — the same split the functions
     * have. These become something a workspace can point at when
     * [PluginObjectRegistry] makes `workflow_object` rows of them, and that is
     * a separate step because a reference is an id and a plugin has no ids.
     */
    @Column(name = "declared_objects", nullable = false, columnDefinition = "text")
    var declaredObjects: String = "[]",

    /**
     * The value types the plugin defines, as JSON. Issue #377.
     *
     * A name over a base type - `SlackUser` over string - with what it needs to
     * be told and whether it can complete and check a value. Replaced wholesale
     * on every load, the way the objects are; the calls that complete and check
     * go to the plugin's source through [PluginTypes], never to what is kept
     * here, which is only the declaration.
     */
    @Column(name = "declared_types", nullable = false, columnDefinition = "text")
    var declaredTypes: String = "[]",

    /**
     * What the plugin answered when asked what it has to be told, as JSON.
     *
     * The plugin's half of the bargain: it says what it needs and a workspace says
     * what those come to. Kept on the plugin rather than beside the settings
     * because it is the plugin's statement, and it is replaced wholesale every time
     * the plugin is loaded — the same way its functions are.
     */
    @Column(name = "declared_parameters", nullable = false, columnDefinition = "text")
    var declaredParameters: String = "[]",

    /**
     * What JavaScript the plugin says it needs, as JSON, as of this load.
     *
     * The plugin's claim, replaced wholesale every time it is loaded — the same
     * way its functions and its parameters are. It grants nothing on its own.
     */
    @Column(name = "declared_permissions", nullable = false, columnDefinition = "text")
    var declaredPermissions: String = "[]",

    /**
     * What a person agreed to, as JSON. The only thing that is ever relaxed.
     *
     * Kept apart from [declaredPermissions] because they are two different facts,
     * and the whole design turns on that. A plugin edited to need more declares
     * more; what was accepted still names only what it was accepted for, so the
     * new one is not covered and loading is refused until somebody accepts it
     * afresh. An escalation cannot inherit an acceptance because the acceptance
     * is a list rather than a yes.
     */
    @Column(name = "accepted_permissions", nullable = false, columnDefinition = "text")
    var acceptedPermissions: String = "[]",

    /**
     * What it asks the *server* to do on its behalf, as JSON, as of this load.
     *
     * Kept apart from [declaredPermissions] because they are different in kind
     * and the difference is the point: a permission turns on a language builtin
     * and reaches nothing, while a capability is the server making a call for
     * the plugin. Two lists, granted separately, shown separately. Issue #316.
     */
    @Column(name = "declared_capabilities", nullable = false, columnDefinition = "text")
    var declaredCapabilities: String = "[]",

    /** What a person agreed to of those, as JSON. Read the note on acceptedPermissions. */
    @Column(name = "accepted_capabilities", nullable = false, columnDefinition = "text")
    var acceptedCapabilities: String = "[]",

    /** Null for a plugin that asks for nothing: there was nothing to accept. */
    @Column(name = "permissions_accepted_at")
    var permissionsAcceptedAt: OffsetDateTime? = null,

    /**
     * Who accepted it.
     *
     * Here rather than only in the audit log, because the question somebody asks
     * later is "what is this plugin allowed to do, and who said so" — and an
     * answer that has to be assembled out of a log is an answer nobody assembles.
     */
    @Column(name = "permissions_accepted_by", length = 120)
    var permissionsAcceptedBy: String? = null,

    @Column(name = "uploaded_at", nullable = false)
    var uploadedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "uploaded_by", nullable = false, length = 120)
    var uploadedBy: String = "",

    /**
     * Whether this plugin is switched on.
     *
     * The reversible half of unloading. Off keeps everything — the row, its
     * edited functions, every workspace's answers to its parameters — and
     * offers nothing: [PluginFunctionRegistry] keeps the function rows so a
     * graph still draws, and the callers refuse a call through them.
     *
     * Survives a re-upload on purpose: somebody who switched a plugin off has
     * said something about the plugin, not about the file, and a new version
     * arriving is not them changing their mind.
     */
    @Column(nullable = false)
    var enabled: Boolean = true,


    /**
     * The marketplace key this was installed from, and the version at that
     * moment. Null for a plugin loaded from a file or somebody's own URL.
     *
     * Kept so a catalog listing and an installed row can be lined up — the
     * screen compares the version against the catalog's to offer an update.
     * Nothing here is ever re-checked against the marketplace on its own: a
     * server that phoned home to compare versions would be a server that
     * phones home.
     */
    @Column(name = "marketplace_key", length = 64)
    var marketplaceKey: String? = null,

    @Column(name = "marketplace_version", length = 32)
    var marketplaceVersion: String? = null,

    /**
     * The face this plugin wears: the SVG itself, or an emoji standing as
     * itself.
     *
     * Copied in at install rather than pointed at. The marketplace hosts the
     * file, but a plugin loaded here has to draw on a screen whose
     * installation may never reach the marketplace again — so the bytes come
     * across once and live with the row, the way the source does. Null for a
     * plugin loaded from a file, which brought no face.
     */
    @Column(columnDefinition = "text")
    var icon: String? = null,

    /**
     * The same glyph in white, for a dark ground. Null where the plugin has
     * only the one, and then [icon] is drawn on both.
     *
     * Copied in at install beside [icon], and for the same reason: an
     * installation that can no longer reach the marketplace still has to draw
     * its plugins, on whichever ground the person looking has chosen.
     */
    @Column(name = "icon_dark", columnDefinition = "text")
    var iconDark: String? = null,

    /**
     * What the plugin says it is for, in a line — from the `plugin.json`
     * beside it. Null for one that ships no manifest.
     */
    @Column(columnDefinition = "text")
    var summary: String? = null,

    /** Who wrote it, as the manifest says. */
    @Column(length = 200)
    var author: String? = null,

    /**
     * What the plugin calls its own version.
     *
     * Not [marketplaceVersion], which is what the catalog offered it as: the
     * two are usually equal and mean different things, and the update
     * comparison goes on using the catalog's.
     */
    @Column(length = 32)
    var version: String? = null,
)

interface PluginRepository : JpaRepository<Plugin, Long> {

    fun findAllByOrderByNameAsc(): List<Plugin>

    /** By what the plugin calls itself, which is what a re-upload replaces. */
    fun findByKey(key: String): Plugin?

    /**
     * What every switched-on plugin offers an agent, without the plugin.
     *
     * A question asked on every round of every agent, and of every agent an
     * import carries, and all it needs is the key and the tool declarations.
     * The entity is the bundle and both icons besides - a few hundred
     * kilobytes a plugin - so answering it with `findAll()` read every loaded
     * plugin's source into memory to look at its list of names. Issue #616.
     */
    @Query(
        """
        select new io.mszymanski.orknux.server.plugin.PluginToolDeclarations(p.id, p.key, p.name, p.declaredTools)
        from Plugin p where p.enabled = true
        """,
    )
    fun enabledToolDeclarations(): List<PluginToolDeclarations>

    /**
     * Which plugins there are and which bundle each holds, and nothing else -
     * enough to tell whether anything a plugin declares can have changed since
     * this was last asked. A re-upload changes the hash; switching one on or
     * off changes `enabled`. Issue #616.
     */
    @Query(
        """
        select new io.mszymanski.orknux.server.plugin.PluginFingerprint(p.id, p.sha256, p.enabled)
        from Plugin p order by p.id
        """,
    )
    fun fingerprints(): List<PluginFingerprint>

    /**
     * Every switched-on plugin's skills as it declared them, without the
     * plugin: skills are resolved for every briefing and every `skill_load`,
     * and answering that with `findAllByOrderByNameAsc()` read every bundle
     * and both icons of every plugin each time. Issue #616.
     */
    @Query(
        """
        select new io.mszymanski.orknux.server.plugin.PluginSkillDeclarations(p.id, p.key, p.name, p.declaredSkills)
        from Plugin p where p.enabled = true order by p.name
        """,
    )
    fun enabledSkillDeclarations(): List<PluginSkillDeclarations>

    /**
     * Every plugin with what it declares, and none of what it is: no source, no
     * typescript, no icons. The pickers - an agent's tools, a workflow's
     * actions, a connection's kinds - ask on every page that draws one, and a
     * plugin carrying a bundled renderer is megabytes of source. Reading that
     * whole, twice over for Hibernate's dirty check, to look at a list of names
     * is how an agent's settings page ran a server out of memory. Issue #616.
     */
    @Query(
        """
        select new io.mszymanski.orknux.server.plugin.PluginDeclared(
            p.id, p.key, p.name, p.enabled, p.declaredTools, p.declaredActions, p.declaredConnectionTypes)
        from Plugin p order by p.name
        """,
    )
    fun declared(): List<PluginDeclared>
}

/** A plugin's declarations without its bundle. See [PluginRepository.declared]. */
data class PluginDeclared(
    val id: Long,
    val key: String,
    val name: String,
    val enabled: Boolean,
    val declaredTools: String,
    val declaredActions: String,
    val declaredConnectionTypes: String,
)

/** A plugin's identity and bundle hash. See [PluginRepository.fingerprints]. */
data class PluginFingerprint(val id: Long, val sha256: String, val enabled: Boolean)

/** A switched-on plugin's skills as it declared them. See [PluginRepository.enabledSkillDeclarations]. */
data class PluginSkillDeclarations(
    val id: Long,
    val key: String,
    val name: String,
    val declaredSkills: String,
)

/** A switched-on plugin's tools as it declared them, and enough of the plugin to name them. See [PluginRepository.enabledToolDeclarations]. */
data class PluginToolDeclarations(
    val id: Long,
    val key: String,
    val name: String,
    val declaredTools: String,
)

/**
 * What the screen is told about a plugin.
 *
 * The source is not on it. A list of what is loaded does not need the text, and
 * a plugin can be a few hundred kilobytes — sending every one of them to draw a
 * table is a waste, and it will still be a waste when there are twenty.
 */
data class PluginView(
    val id: String,
    /** What the plugin calls itself, and the prefix on everything it declares. */
    val key: String,
    val name: String,
    val filename: String,
    val sizeBytes: Double,
    val apiVersion: Int,
    val declaredFunctions: List<PluginFunctionView>,
    /** What it says it has to be told before it can work. */
    val declaredParameters: List<PluginParameterView>,
    /** What it says it needs, and what was agreed to. Equal, for a loaded plugin. */
    val permissions: List<PluginPermissionView>,
    /** ISO-8601. Null for a plugin that asked for nothing; there was nothing to accept. */
    val permissionsAcceptedAt: String?,
    val permissionsAcceptedBy: String?,
    val sha256: String,
    val uploadedAt: String,
    val uploadedBy: String,
    /** The connection kinds it declares, for a workspace to hold hosts of. Issue #363. */
    val connectionTypes: List<PluginConnectionTypeView> = emptyList(),
    /** The workflow actions it declares, for an Action node to be pointed at. Issue #438. */
    val actions: List<PluginActionView> = emptyList(),
    /** Whether it is switched on; off keeps everything and offers nothing. */
    val enabled: Boolean = true,
    /** The files it ships with, by path. Empty for a single-file plugin. */
    val libraries: List<String> = emptyList(),
    /** The instruction sets it brings, offered to agents as a catalog of its own. */
    val skills: List<PluginSkillView> = emptyList(),
    /** The shapes it exports, available in every workspace under its key. */
    val objects: List<PluginObjectView> = emptyList(),
    /** The value types it defines; see [PluginTypeView]. Issue #377. */
    val types: List<PluginTypeView> = emptyList(),
    /** Where it came from, when that was the marketplace. */
    val marketplaceKey: String? = null,
    val marketplaceVersion: String? = null,
    /** The SVG it wears, or an emoji; copied in at install. Null for a file. */
    val icon: String? = null,
    /** The same, in white, for a dark ground. Null where there is only the one. */
    val iconDark: String? = null,
    /** What it says about itself, where it ships a `plugin.json`. */
    val summary: String? = null,
    val author: String? = null,
    val version: String? = null,
)

/**
 * One function a plugin says it offers.
 *
 * Carries the signature ready to read, the way a workspace's own functions do —
 * the same string in the same shape, so a list can show either without knowing
 * which it has.
 */
data class PluginFunctionView(
    val name: String,
    val description: String?,
    val params: List<PluginFunctionParamView>,
    val returnType: String,
    /**
     * The plugin's own name for the shape it returns, where it returns one.
     *
     * A name rather than a reference: the plugin has no ids, and turning this
     * into one is [PluginFunctionRegistry]'s step, at the moment the rows it
     * would point at exist.
     */
    val returnObject: String? = null,
    val signature: String,
    /** The `run` as the plugin wrote it, for the editor. Reference only. */
    val source: String? = null,
)

/**
 * One argument a plugin's function takes.
 *
 * [objectName] is set where the type names one of the plugin's own shapes.
 * [required] false means a call may leave it out, and [default] is the JSON
 * that arrives instead - which is what a plugin used to spell as "0 for the
 * default" in a sentence a model read on every call.
 */
data class PluginFunctionParamView(
    val name: String,
    val type: String,
    val objectName: String? = null,
    val required: Boolean = true,
    val default: String? = null,
)

/**
 * One instruction set a plugin brings.
 *
 * [content] is the markdown an agent reads, frontmatter and all — stored as it
 * will be handed over rather than assembled on the way out, so what a screen
 * shows and what an agent loads are the same text.
 */
/**
 * One shape a plugin exports, as it declared it.
 *
 * The plugin's own spelling throughout — `of` names another of *this plugin's*
 * objects. What it becomes on the row is the registry's business; this is the
 * declaration, and the declaration is what the plugin said.
 */
data class PluginObjectView(
    val name: String,
    val description: String?,
    val properties: List<PluginObjectPropertyView>,
)

/** One field of an exported shape. */
data class PluginObjectPropertyView(
    val name: String,
    /** STRING, NUMBER, BOOLEAN, OBJECT or ARRAY. */
    val kind: String,
    /** The object it points at, or what the array holds, by the plugin's own name. */
    val of: String?,
    /** What an array holds when it holds scalars. */
    val elementKind: String?,
    val description: String?,
)

data class PluginSkillView(
    val name: String,
    /** Derived from the name, the way a workspace skill's is. Issue #381. */
    val key: String,
    val description: String?,
    val content: String,
)

/**
 * One tool a plugin says it offers to agents.
 *
 * [proxyOf] set means the tool stands in front of the plugin's function of that
 * name: the params and return type here were copied from it when the plugin was
 * inspected, and a call goes down the function path - edits and all. Null means
 * the tool runs its own code out of the plugin's `tools()`.
 */
data class PluginToolView(
    val name: String,
    val description: String?,
    val params: List<PluginFunctionParamView>,
    val returnType: String,
    /** The plugin's own name for the shape it answers with, where it answers one. */
    val returnObject: String? = null,
    val proxyOf: String?,
)

/**
 * A kind of connection a plugin declares, as the interface sees it. Issue #363.
 *
 * [id] is the plugin key and the declared name joined - `prometheus/server` -
 * which is what a connection stores in `pluginType` and what a picker narrows
 * by; [label] is what a person reads on the type menu and the list.
 */
data class PluginConnectionTypeView(
    val id: String,
    val name: String,
    val label: String,
    val description: String?,
    val urlPlaceholder: String?,
    val pluginKey: String,
    val pluginName: String,
)

/**
 * One workflow action a plugin declares, as the editor and the runner see it.
 * Issue #438.
 *
 * Addressed by [pluginKey] and [name] together, which is what an Action of
 * subtype PLUGIN_ACTION stores: two plugins may both declare `respond`, and a
 * name on its own would not say whose. [label] is what the node picker shows.
 * The parameter and output types are [io.mszymanski.orknux.server.action.ValueType]
 * names, already checked at load, so the node's ports can be drawn from them
 * without asking the plugin anything.
 */
data class PluginActionView(
    val pluginKey: String,
    val pluginName: String,
    val name: String,
    val label: String,
    val description: String?,
    val parameters: List<PluginActionParamView>,
    val outputs: List<PluginActionParamView>,
)

/** One input a plugin action takes, or one output it hands on; [type] is a `ValueType` name. */
data class PluginActionParamView(
    val name: String,
    val type: String,
    val required: Boolean,
    val description: String?,
)

/**
 * One tool a plugin offers to agents, as the grant list is told about it.
 *
 * [name] carries the plugin's key prefix - it is what goes on the grant list.
 * [functionId] is set for a tool fronting one of the plugin's functions, so
 * the list can offer the jump to the function's page; a tool with a run of
 * its own has no page to go to.
 */
data class PluginAgentToolView(
    val name: String,
    val description: String?,
    val plugin: String,
    val functionId: String?,
)

/**
 * One thing a plugin says it has to be told, as the screen shows it.
 *
 * [secret] is the plugin's own claim about what it is asking for, not a promise
 * about how it is kept: the server refuses to store a secret parameter as a
 * literal, so the only way to fill one in is to point at a variable.
 */
data class PluginParameterView(
    val name: String,
    val description: String?,
    val type: String,
    val required: Boolean,
    val secret: Boolean,
    /**
     * Which kind of connection this names, when [type] is `connection`.
     *
     * What the settings form narrows its picker by. Null for every other type,
     * and null on a declaration written before connection parameters existed —
     * which is the same thing said twice, since such a declaration has no
     * connection parameter to be missing it.
     */
    val connectionType: String? = null,
    /**
     * The values this may take, where the plugin knows them all.
     *
     * Empty where anything typed will do. A set makes the settings field a
     * picker, which is what saves a plugin hand-checking the string and
     * writing the sentence that lists the choices.
     */
    val options: List<String> = emptyList(),
)

/**
 * One piece of JavaScript a plugin was granted, as the screen shows it.
 *
 * Shown to whoever looks at the plugin list, not only to whoever accepted it. A
 * decision about what code may do that lives in a dialog somebody clicked through
 * last month is a decision nobody can audit.
 */
data class PluginPermissionView(
    val name: String,
    /** What it gives, in the words the person accepting it was shown. */
    val summary: String,
)

/**
 * The plugin API versions this server knows.
 *
 * A plugin says which one it was written against and is refused if that is not on
 * this list. The list is here rather than inline so that adding a version, or
 * dropping support for an old one, is one edit in one place.
 *
 * Named `PluginApiVersions` rather than the obvious `PluginApi`: the controller
 * beside it is `PluginAPI`, and on a case-insensitive filesystem those two are
 * the same class file. The JVM notices at load time, not at compile time, so it
 * fails as a `NoClassDefFoundError` on the first request rather than in the build.
 */
object PluginApiVersions {

    /** What a plugin written today should answer. */
    const val CURRENT = 1

    /** Every version this server can still run. */
    val SUPPORTED = setOf(1)
}

/**
 * The declarations are passed in rather than parsed here: reading them needs the
 * application's JSON mapper, and a view of a row should not be reaching for a
 * bean of its own to build itself.
 */
fun Plugin.view(
    declared: List<PluginFunctionView>,
    parameters: List<PluginParameterView> = emptyList(),
    permissions: List<PluginPermissionView> = emptyList(),
    /** The paths of the files it ships with; read beside the declarations. */
    libraries: List<String> = emptyList(),
    /** The instruction sets it brings; read beside the declarations. */
    skills: List<PluginSkillView> = emptyList(),
    /** The shapes it exports; read beside the declarations. */
    objects: List<PluginObjectView> = emptyList(),
    types: List<PluginTypeView> = emptyList(),
    /** The kinds of host it declares; read beside the declarations. Issue #363. */
    connectionTypes: List<PluginConnectionTypeView> = emptyList(),
    /** The workflow actions it declares; read beside the declarations. Issue #438. */
    actions: List<PluginActionView> = emptyList(),
): PluginView = PluginView(
    id = requireNotNull(id).toString(),
    key = key,
    name = name,
    filename = filename,
    // GraphQL has no long; the rest of this schema reports sizes as floats too,
    // and a plugin is nowhere near where a double stops counting exactly.
    sizeBytes = sizeBytes.toDouble(),
    apiVersion = apiVersion,
    declaredFunctions = declared,
    declaredParameters = parameters,
    permissions = permissions,
    permissionsAcceptedAt = permissionsAcceptedAt?.toString(),
    permissionsAcceptedBy = permissionsAcceptedBy,
    sha256 = sha256,
    uploadedAt = uploadedAt.toString(),
    uploadedBy = uploadedBy,
    enabled = enabled,
    libraries = libraries,
    skills = skills,
    connectionTypes = connectionTypes,
    actions = actions,
    objects = objects,
    types = types,
    marketplaceKey = marketplaceKey,
    marketplaceVersion = marketplaceVersion,
    icon = icon,
    iconDark = iconDark,
    summary = summary,
    author = author,
    version = version,
)
