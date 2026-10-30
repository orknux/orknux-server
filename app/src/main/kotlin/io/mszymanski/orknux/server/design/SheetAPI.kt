package io.mszymanski.orknux.server.design

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import io.mszymanski.orknux.server.attachment.EditorChatFiles
import io.mszymanski.orknux.server.attachment.EditorChatTurn
import io.mszymanski.orknux.server.embedded.SheetReader
import io.mszymanski.orknux.server.live.ItemSaved
import io.mszymanski.orknux.server.live.LiveKind
import io.mszymanski.orknux.server.live.LiveRooms
import io.mszymanski.orknux.server.security.ActingIn
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Lazy
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.util.Base64

/**
 * A workspace's sheets, and the one door every change to them comes through.
 *
 * Anybody who can see the workspace may work in one, as anybody may write a
 * document in Design. The orknux_* sheet tools save through this same class
 * ([io.mszymanski.orknux.server.mcp.SheetTools]), so what a model writes is
 * checked by the same [SheetWorkbook], held to the same Tunable and written in
 * the audit log in the same words, under the name of whoever asked.
 */
@Controller
class SheetAPI(
    private val sheets: SheetRepository,
    private val audit: WorkspaceAuditRecorder,
    private val access: WorkspaceAccess,
    private val mapper: ObjectMapper,
    private val reader: SheetReader,
    /** Lazy: the chat reaches the orknux_* tools, which save through here. */
    @param:Lazy private val chat: SheetChat,
    /** What a turn's files say, for the model. */
    private val files: EditorChatFiles,
    private val tuning: Tuning = Tuning.DEFAULTS,
    /** Who has it open, told when it is saved from any door; and the audit's once-per-visit. */
    private val published: ApplicationEventPublisher? = null,
    private val rooms: LiveRooms? = null,
) {

    private val workbook = SheetWorkbook(mapper)

    val engine = SheetEngine()

    /** The functions a formula may call: what the engine works out, so a name offered is a name that runs. */
    @QueryMapping
    fun sheetFunctions(@Argument workspaceId: Long): List<String> {
        access.requireVisible(workspaceId)
        return (
            org.apache.poi.ss.formula.eval.FunctionEval.getSupportedFunctionNames() +
                org.apache.poi.ss.formula.atp.AnalysisToolPak.getSupportedFunctionNames()
            ).filterNot { it.startsWith("_") }.distinct().sorted()
    }

    @QueryMapping
    @Transactional(readOnly = true)
    fun sheets(@Argument workspaceId: Long): List<SheetView> {
        access.requireVisible(workspaceId)
        return sheets.findByWorkspaceIdOrderByUpdatedAtDescIdDesc(workspaceId).map(::view)
    }

    /** One sheet, or null where there is none this person may see. */
    @QueryMapping
    @Transactional(readOnly = true)
    fun sheet(@Argument id: Long): SheetView? {
        val held = sheets.findByIdOrNull(id) ?: return null
        access.requireVisible(held.workspaceId)
        return view(held)
    }

    /** What a workbook the editor holds comes to, saved or not. */
    @QueryMapping
    fun sheetValues(@Argument workspaceId: Long, @Argument workbook: String): String {
        access.requireVisible(workspaceId)
        return values(checked(workbook))
    }

    /** Worked out only when it is asked for: the list asks for none, and a large sheet is not cheap. */
    @SchemaMapping(typeName = "Sheet", field = "values")
    fun values(sheet: SheetView): String = values(workbook.read(sheet.workbook))

    @MutationMapping
    @Transactional
    fun createSheet(@Argument workspaceId: Long, @Argument name: String, @Argument workbook: String?): SheetView {
        access.requireVisible(workspaceId)
        val made = make(workspaceId, cleanName(name), checked(workbook))
        audit.record(workspaceId, WorkspaceAuditCategory.WORKSPACE, "Sheet ${made.name} added")
        return view(made)
    }

    @MutationMapping
    @Transactional
    fun renameSheet(@Argument id: Long, @Argument name: String): SheetView {
        val held = find(id)
        val was = held.name
        val called = cleanName(name)
        if (called == was) return view(held)
        held.name = called
        touch(held)
        audit.record(held.workspaceId, WorkspaceAuditCategory.WORKSPACE, "Sheet $was renamed to $called")
        return view(sheets.save(held))
    }

    /**
     * Writes a whole workbook over the one held.
     *
     * @param revision the revision the workbook was made from. Where it is given
     *   and is not the one held, somebody - or a model - saved in between, and
     *   writing this over it would lose what they did, so it is refused.
     */
    @MutationMapping
    @Transactional
    fun saveSheet(@Argument id: Long, @Argument workbook: String, @Argument revision: Int?, @Argument viewer: String?): SheetView {
        // Claimed before it is read, so two saves over one revision cannot both pass; see DiagramRepository.holdRevision.
        if (revision != null && sheets.holdRevision(id, revision) == 0) throw SheetChangedException(find(id).name)
        val held = find(id)
        if (revision != null && revision != held.revision) throw SheetChangedException(held.name)
        return write(held, checked(workbook), viewer?.takeIf { it.isNotBlank() })
    }

    /** The same save, from the orknux_* tools, which hold tabs rather than text. */
    @Transactional
    fun saveTabs(id: Long, tabs: List<SheetTab>): SheetView {
        val held = find(id)
        return write(held, held(tabs))
    }

    @MutationMapping
    @Transactional
    fun deleteSheet(@Argument id: Long): Boolean {
        val held = find(id)
        sheets.delete(held)
        audit.record(held.workspaceId, WorkspaceAuditCategory.WORKSPACE, "Sheet ${held.name} removed")
        return true
    }

    /**
     * A sheet made from a file somebody brought: every tab, with what its
     * cells held and its formulas. Read by [SheetReader] - the reader an agent
     * reads an attached workbook through - so a file either reads the same in
     * both places or is refused in the same words.
     */
    @MutationMapping
    @Transactional
    fun importSheet(@Argument workspaceId: Long, @Argument filename: String, @Argument file: String, @Argument name: String?): SheetView {
        access.requireVisible(workspaceId)
        val bytes = try {
            Base64.getMimeDecoder().decode(file.substringAfter("base64,", file))
        } catch (_: IllegalArgumentException) {
            throw SheetImportRefusedException("it did not arrive as base64")
        }
        val most = tuning[Tunable.SHEET_IMPORT_MB]
        if (bytes.size > most.toLong() * 1024 * 1024) {
            throw SheetImportRefusedException("it is ${bytes.size / (1024 * 1024)} MB, and a sheet is made from a file of $most MB at most")
        }
        val limits = SheetReader.Limits(
            rows = Sheets.ROWS_MOST,
            characters = Int.MAX_VALUE,
            unpackedBytes = tuning[Tunable.SHEET_UNPACKED_MB].toLong() * 1024 * 1024,
        )
        val tabs = if (filename.lowercase().endsWith(".csv") || filename.lowercase().endsWith(".tsv")) {
            val text = String(bytes, StandardCharsets.UTF_8)
            engine.imported(reader.csv(text, 1, null, limits, delimiter = if (filename.lowercase().endsWith(".tsv")) '\t' else null)) {
                SheetReader.Reading.Refused("a CSV has one tab")
            }.map { it.copy(name = Sheets.FIRST_TAB) }
        } else {
            engine.imported(reader.workbook(bytes, null, 1, null, limits)) { tab -> reader.workbook(bytes, tab, 1, null, limits) }
        }
        val called = cleanName(name?.takeIf { it.isNotBlank() } ?: filename.substringBeforeLast('.').ifBlank { filename })
        val made = make(workspaceId, called, held(tabs))
        audit.record(workspaceId, WorkspaceAuditCategory.WORKSPACE, "Sheet ${made.name} imported from $filename")
        return view(made)
    }

    /**
     * The stored workbook as a file to keep - the address the editor's Export
     * opens - and said in the audit log, since a file leaving the server is a
     * copy somebody now holds.
     */
    fun export(id: Long, format: String, tab: Int?): SheetFile {
        val held = find(id)
        val tabs = workbook.read(held.workbook)
        val made = when (format.lowercase()) {
            "xlsx" -> SheetFile(fileName(held.name, "xlsx"), XLSX, engine.xlsx(tabs))
            "csv" -> {
                val chosen = (tab ?: 0).coerceIn(0, tabs.size - 1)
                val stem = if (tabs.size > 1) "${held.name} - ${tabs[chosen].name}" else held.name
                SheetFile(fileName(stem, "csv"), "text/csv;charset=UTF-8", engine.csv(tabs, chosen).toByteArray(StandardCharsets.UTF_8))
            }
            else -> throw SheetFormatUnknownException(format)
        }
        audit.record(held.workspaceId, WorkspaceAuditCategory.WORKSPACE, "Sheet ${held.name} exported as ${format.lowercase()}")
        return made
    }

    /**
     * Asks a chat model to work in a sheet, and answers with what it said and
     * the sheet as it now stands.
     *
     * Not transactional: a round can take a minute, and every change the model
     * makes is its own save through [saveTabs], seen by the editor the moment
     * the answer comes back.
     */
    @MutationMapping
    fun askSheet(@Argument id: Long, @Argument modelId: Long, @Argument messages: List<SheetChatTurnInput>): SheetAnswerView {
        val held = find(id)
        val turns = files.turns(held.workspaceId, who(), messages.map { EditorChatTurn(it.role, it.content, it.attachmentIds.orEmpty()) })
        if (turns.none { it.role == "user" }) throw SheetAnswerFailedException("there is nothing to answer")
        val answered = chat.answer(view(held), modelId, turns, who())
        val now = find(id)
        return SheetAnswerView(answer = answered, changed = now.revision != held.revision, sheet = view(now))
    }

    /** The sheet by id, checked to be one this caller may see. */
    fun find(id: Long): Sheet {
        val held = sheets.findByIdOrNull(id) ?: throw SheetNotFoundException(id)
        access.requireVisible(held.workspaceId)
        return held
    }

    /** Tabs read from a workbook, held to the Tunable. */
    fun checked(workbook: String?): List<SheetTab> = held(this.workbook.read(workbook))

    /** The values of [tabs], as the Sheet's `values` field gives them. */
    fun values(tabs: List<SheetTab>): String = mapper.writeValueAsString(
        mapOf(
            "tabs" to engine.values(tabs).map { (name, values) ->
                mapOf("name" to name, "values" to values.mapValues { it.value.asMap() })
            },
        ),
    )

    fun view(sheet: Sheet): SheetView {
        val tabs = runCatching { workbook.read(sheet.workbook) }.getOrDefault(emptyList())
        return SheetView(
            id = requireNotNull(sheet.id),
            workspaceId = sheet.workspaceId,
            name = sheet.name,
            workbook = sheet.workbook,
            tabCount = tabs.size,
            cellCount = tabs.sumOf { it.cells.size },
            revision = sheet.revision,
            createdBy = sheet.createdBy,
            createdAt = sheet.createdAt.toString(),
            updatedBy = sheet.updatedBy,
            updatedAt = sheet.updatedAt.toString(),
        )
    }

    private fun make(workspaceId: Long, name: String, tabs: List<SheetTab>): Sheet {
        val who = who()
        return sheets.save(Sheet(workspaceId = workspaceId, name = name, workbook = workbook.write(tabs), createdBy = who, updatedBy = who))
    }

    private fun write(held: Sheet, tabs: List<SheetTab>, viewer: String? = null): SheetView {
        held.workbook = workbook.write(tabs)
        held.revision += 1
        touch(held)
        val saved = sheets.save(held)
        val id = requireNotNull(saved.id)
        // Once per visit from an editor, which saves as it is edited; every time from anything else.
        if (viewer == null || rooms?.firstSave(LiveRooms.Key(LiveKind.SHEET, id), viewer) != false) {
            audit.record(held.workspaceId, WorkspaceAuditCategory.WORKSPACE, "Sheet ${held.name} saved with ${tabs.sumOf { it.cells.size }} cells")
        }
        // From every door, so whoever has it open sees it.
        published?.publishEvent(ItemSaved(LiveKind.SHEET, id, saved.revision.toLong(), viewer, who()))
        return view(saved)
    }

    private fun held(tabs: List<SheetTab>): List<SheetTab> {
        val most = tuning[Tunable.SHEET_CELLS_MOST]
        val count = tabs.sumOf { it.cells.size }
        if (count > most) throw SheetTooLargeException(count, most)
        return tabs
    }

    private fun touch(held: Sheet) {
        held.updatedBy = who()
        held.updatedAt = OffsetDateTime.now()
    }

    private fun cleanName(name: String): String =
        name.trim().takeIf { it.isNotEmpty() && it.length <= Sheets.NAME_LENGTH } ?: throw SheetNameInvalidException()

    /** Whoever is signed in; an agent working through the orknux_* tools with nobody signed in, by its name. */
    private fun who(): String {
        val signedIn = SecurityContextHolder.getContext().authentication
            ?.takeIf { it.isAuthenticated && it !is AnonymousAuthenticationToken }
        return signedIn?.name ?: ActingIn.actor() ?: "orknux"
    }

    companion object {
        const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

        /** A name a file can have on every system it will be saved on. */
        fun fileName(name: String, extension: String): String =
            name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").trim().ifEmpty { "sheet" }.take(150) + "." + extension
    }
}

/** A sheet as the list and the editor read it. Times are ISO-8601. */
data class SheetView(
    val id: Long,
    val workspaceId: Long,
    val name: String,
    /** `{"tabs": [...]}`. */
    val workbook: String,
    val tabCount: Int,
    val cellCount: Int,
    val revision: Int,
    val createdBy: String,
    val createdAt: String,
    val updatedBy: String,
    val updatedAt: String,
)

/** A sheet written out as a file: its name, its type and its bytes. */
data class SheetFile(val filename: String, val contentType: String, val bytes: ByteArray)

data class SheetAnswerView(
    val answer: String,
    /** Whether the model saved anything, so the editor knows to take the sheet up. */
    val changed: Boolean,
    val sheet: SheetView,
)

/** One turn of the editor's conversation, which the editor keeps and sends up whole each time. */
data class SheetChatTurnInput @JsonCreator constructor(
    @JsonProperty("role") val role: String,
    @JsonProperty("content") val content: String,
    /** Files uploaded through the attachments endpoint, sent with this turn. */
    @JsonProperty("attachmentIds") val attachmentIds: List<Long>? = null,
)
