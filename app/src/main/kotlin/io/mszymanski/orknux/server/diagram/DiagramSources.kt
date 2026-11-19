package io.mszymanski.orknux.server.diagram

import io.mszymanski.orknux.server.action.FunctionCaller
import io.mszymanski.orknux.server.action.WorkflowFunction
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.plugin.PluginParameters
import io.mszymanski.orknux.server.plugin.PluginRepository
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.script.ScriptResult
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

/**
 * What a code or a Jira shape last read, kept so that opening a diagram draws
 * it at once and asks nothing of GitHub or Jira until the copy is older than
 * the workspace says it may be. One row per workspace, function and source:
 * keyed by the function too, so a plugin loaded again under a new row never
 * serves what the old one answered; a Jira connection chosen anew throws the
 * workspace's Jira copies away ([DiagramSources.forget]). V689.
 */
@Entity
@Table(name = "diagram_fetched")
class DiagramFetched(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    @Column(name = "function_id", nullable = false)
    val functionId: Long,

    /** The shape's source as it is saved: a GitHub file's address, or an issue key. */
    @Column(nullable = false, length = Diagrams.LINK_LENGTH)
    val source: String,

    /** What it answered: the lines of code, or the issue as JSON. Null until one read succeeded. */
    @Column(columnDefinition = "text")
    var body: String? = null,

    /** When it was last asked, whatever it answered - a failure waits as long as a success before it is asked again. */
    @Column(name = "fetched_at", nullable = false)
    var fetchedAt: OffsetDateTime,

    /** Why the last read failed, where it did; the body is then the last one that worked. */
    @Column(columnDefinition = "text")
    var failed: String? = null,
)

interface DiagramFetchedRepository : JpaRepository<DiagramFetched, Long> {
    fun findByWorkspaceIdAndFunctionIdAndSource(workspaceId: Long, functionId: Long, source: String): DiagramFetched?
    fun findByWorkspaceIdAndFunctionIdAndSourceIn(workspaceId: Long, functionId: Long, sources: Collection<String>): List<DiagramFetched>

    @org.springframework.transaction.annotation.Transactional
    fun deleteByWorkspaceIdAndFunctionId(workspaceId: Long, functionId: Long)
}

/**
 * Which shapes read from outside, and the plugin function each reads with.
 *
 * Named here, on purpose and against the rule that core names no plugin's
 * tool: a code shape is a GitHub file and a Jira shape a Jira issue, and the
 * person deciding said these read through those two plugins rather than
 * through a function each workspace picks - a picker offered the GitHub
 * plugin's fileHistory and reviewComment beside openFile, and both took the
 * same owner, repo and path. A workspace without the plugin draws the shape
 * saying so, and nothing tells a model to call either.
 */
enum class DiagramSourceKind(val kind: DiagramNodeKind, val plugin: String, val function: String) {
    CODE(DiagramNodeKind.CODE, "github", "openFile"),
    JIRA(DiagramNodeKind.JIRA, "jira", "openIssue"),
    ;

    /** The function's row name: the plugin's key before the name it declared. */
    val row: String get() = "${plugin}_$function"

    companion object {
        fun of(kind: DiagramNodeKind): DiagramSourceKind? = entries.firstOrNull { it.kind == kind }

        /** What a Jira connection of the Jira plugin's own kind is called on its row: the plugin's key and its type, joined. */
        const val JIRA_CONNECTION = "jira/jira"
    }
}

/** One shape's copy, as the editor draws it. */
data class DiagramSourceView(
    val kind: DiagramSourceKind,
    val source: String,
    /** The code's lines, or the issue as JSON; null where nothing was read yet. */
    val body: String?,
    val fetchedAt: String?,
    /** Older than the workspace keeps a copy, or never read: worth asking again. */
    val stale: Boolean,
    val failed: String?,
    /** Whether the plugin this kind reads through is loaded and switched on. */
    val configured: Boolean,
)

/**
 * Reads what code and Jira shapes show: a GitHub file through the GitHub
 * plugin's openFile, with its token, and a Jira issue through the Jira
 * plugin's openIssue, through the connection the workspace chose for diagrams
 * or else the one the plugin is pointed at.
 *
 * Two halves on purpose. [cached] reads only the table and is what a diagram
 * opens with; [fetch] calls out and is asked afterwards, shape by shape, for
 * the ones that are stale and on screen - so a diagram is drawn at once and a
 * slow GitHub never holds it up.
 */
@Service
class DiagramSources(
    private val fetched: DiagramFetchedRepository,
    private val workspaces: WorkspaceRepository,
    private val functions: WorkflowFunctionRepository,
    private val plugins: PluginRepository,
    private val pluginParameters: PluginParameters,
    private val caller: FunctionCaller,
    private val tuning: Tuning,
    private val mapper: ObjectMapper,
) {

    /** The plugin function [kind] reads with, or null where that plugin is not loaded or is switched off. */
    fun functionFor(workspace: Workspace, kind: DiagramSourceKind): WorkflowFunction? {
        val plugin = plugins.findByKey(kind.plugin)?.takeIf { it.enabled } ?: return null
        return functions.findByPluginId(plugin.id!!).firstOrNull { it.name == kind.row }
    }

    /**
     * The plugin settings a read of [kind] is made with instead of the
     * workspace's: a Jira connection chosen for diagrams, handed over as the
     * plugin's `jira` parameter would hand it. None where none was chosen, or
     * it has gone.
     */
    private fun settingsFor(workspace: Workspace, kind: DiagramSourceKind, function: WorkflowFunction): Map<String, String> {
        if (kind != DiagramSourceKind.JIRA) return emptyMap()
        val connectionId = workspace.diagramJiraConnectionId ?: return emptyMap()
        val plugin = function.pluginId?.let { plugins.findByIdOrNull(it) } ?: return emptyMap()
        val handle = pluginParameters.ownHandle(plugin, connectionId, workspace.id!!) ?: return emptyMap()
        return mapOf("jira" to handle)
    }

    /** What was kept of [kind] for a workspace, thrown away: read again through what it reads through now. */
    fun forget(workspace: Workspace, kind: DiagramSourceKind) {
        val function = functionFor(workspace, kind) ?: return
        fetched.deleteByWorkspaceIdAndFunctionId(workspace.id!!, function.id!!)
    }

    /** What is kept for each of [shapes] - by kind and source - without calling anything. */
    fun cached(workspace: Workspace, shapes: Collection<Pair<DiagramSourceKind, String>>): List<DiagramSourceView> =
        shapes.groupBy({ it.first }, { it.second }).flatMap { (kind, sources) ->
            val function = functionFor(workspace, kind)
            val held = function?.let { fn ->
                fetched.findByWorkspaceIdAndFunctionIdAndSourceIn(workspace.id!!, fn.id!!, sources.toSet()).associateBy { it.source }
            }.orEmpty()
            sources.distinct().map { source -> view(workspace, kind, source, function != null, held[source]) }
        }

    /** One shape's copy, read again where it is stale or [force]d; a failure keeps the last copy that worked. */
    fun fetch(workspace: Workspace, kind: DiagramSourceKind, source: String, force: Boolean): DiagramSourceView {
        val workspaceId = workspace.id!!
        val function = functionFor(workspace, kind) ?: return view(workspace, kind, source, false, null)
        val held = fetched.findByWorkspaceIdAndFunctionIdAndSource(workspaceId, function.id!!, source)
        if (!force && held != null && !stale(workspace, held)) return view(workspace, kind, source, true, held)

        val result = caller.call(function, arguments(function, kind, source), context(workspaceId), workspaceId, insteadOfSettings = settingsFor(workspace, kind, function))
        val row = held ?: DiagramFetched(workspaceId = workspaceId, functionId = function.id!!, source = source, fetchedAt = OffsetDateTime.now())
        row.fetchedAt = OffsetDateTime.now()
        when (result) {
            is ScriptResult.Returned -> {
                row.body = body(kind, source, result.json)
                row.failed = null
            }
            is ScriptResult.Failed -> row.failed = "${function.name} ${result.reason}"
        }
        val saved = try {
            fetched.save(row)
        } catch (_: DataIntegrityViolationException) {
            // Two viewers asked at once and the other saved first; theirs is as good.
            fetched.findByWorkspaceIdAndFunctionIdAndSource(workspaceId, function.id!!, source) ?: row
        }
        return view(workspace, kind, source, true, saved)
    }

    /** The arguments by the names the kind passes, in the order the function declares them; the rest take their defaults. */
    private fun arguments(function: WorkflowFunction, kind: DiagramSourceKind, source: String): List<String> {
        val byName: Map<String, Any?> = when (kind) {
            DiagramSourceKind.CODE -> {
                val code = DiagramCodeSource.of(source) ?: throw DiagramDocumentInvalidException("$source is not a GitHub file's address")
                // The whole file, cut here: a reader asked for a stretch may put a line of its own above it.
                mapOf("owner" to code.owner, "repo" to code.repo, "path" to code.path, "ref" to code.ref,
                    "fromLine" to 1, "toLine" to 0, "numbered" to false)
            }
            DiagramSourceKind.JIRA -> mapOf("key" to (DiagramJiraSource.of(source)?.key ?: source))
        }
        return function.params.map { param ->
            if (byName.containsKey(param.name)) mapper.writeValueAsString(byName[param.name]) else param.defaultJson ?: "null"
        }
    }

    private fun context(workspaceId: Long): String = mapper.writeValueAsString(
        mapOf("now" to OffsetDateTime.now().toString(), "timestamp" to System.currentTimeMillis(), "workspaceId" to workspaceId, "diagram" to true),
    )

    /** What is kept of an answer: the asked lines of a file, or the issue as JSON - at most the installation's number of characters. */
    private fun body(kind: DiagramSourceKind, source: String, json: String?): String {
        val most = tuning[Tunable.DIAGRAM_SOURCE_CHARACTERS]
        return when (kind) {
            DiagramSourceKind.CODE -> {
                val read = json?.let { mapper.readTree(it) }
                val text = if (read != null && read.isString) read.stringValue() else read?.toString().orEmpty()
                val code = DiagramCodeSource.of(source)
                val lines = text.split('\n').let { if (it.lastOrNull() == "") it.dropLast(1) else it }.map { it.removeSuffix("\r") }
                val from = (code?.fromLine ?: 1).coerceAtLeast(1)
                val to = (code?.toLine ?: lines.size).coerceAtMost(lines.size)
                val kept = if (from > to) "" else lines.subList(from - 1, to).joinToString("\n")
                kept.take(most)
            }
            DiagramSourceKind.JIRA -> (json ?: "null").take(most)
        }
    }

    private fun stale(workspace: Workspace, row: DiagramFetched): Boolean {
        val minutes = (workspace.diagramFetchMinutes ?: DEFAULT_MINUTES).toLong()
        return row.fetchedAt.plusMinutes(minutes).isBefore(OffsetDateTime.now()) || minutes == 0L
    }

    private fun view(workspace: Workspace, kind: DiagramSourceKind, source: String, configured: Boolean, row: DiagramFetched?) = DiagramSourceView(
        kind = kind,
        source = source,
        body = row?.body,
        fetchedAt = row?.fetchedAt?.toString(),
        stale = row == null || stale(workspace, row),
        failed = row?.failed,
        configured = configured,
    )

    companion object {
        /** How long a copy is kept where a workspace has not said: an hour. */
        const val DEFAULT_MINUTES = 60
    }
}
