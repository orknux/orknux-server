package io.mszymanski.orknux.server.design

import io.mszymanski.orknux.server.graphql.Refusal
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

/**
 * A sheet: a spreadsheet kept in the Design perspective beside the workspace's
 * documents and wireframes, filled in by hand in the editor and by a model
 * through the orknux_* sheet tools.
 *
 * The workbook is one JSON document ([SheetWorkbook]) rather than a row per
 * cell, for the reason a wireframe's drawing is one: it is saved and read
 * whole, and nothing asks for one cell across sheets. It holds what was typed
 * into each cell and never what a formula came to - that is worked out by
 * [SheetEngine] whenever it is asked for, so a stored value can never disagree
 * with the formula beside it. [revision] counts the saves, so a save made
 * against a workbook that has changed since is refused instead of written over
 * what changed it.
 */
@Entity
@Table(name = "sheet")
class Sheet(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    @Column(nullable = false, length = Sheets.NAME_LENGTH)
    var name: String,

    /** `{"tabs": [...]}`, as [SheetWorkbook.write] writes it. */
    @Column(nullable = false, columnDefinition = "text")
    var workbook: String,

    @Column(nullable = false)
    var revision: Int = 1,

    @Column(name = "created_by", nullable = false, length = 255)
    val createdBy: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_by", nullable = false, length = 255)
    var updatedBy: String,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now(),
)

interface SheetRepository : JpaRepository<Sheet, Long> {

    /** Locks the row where it is still at [revision], changing nothing; 0 where somebody saved since. Issue #683. */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @org.springframework.data.jpa.repository.Query("update Sheet s set s.revision = s.revision where s.id = :id and s.revision = :revision")
    fun holdRevision(@org.springframework.data.repository.query.Param("id") id: Long, @org.springframework.data.repository.query.Param("revision") revision: Int): Int

    /** A workspace's sheets, the one worked on last first. */
    fun findByWorkspaceIdOrderByUpdatedAtDescIdDesc(workspaceId: Long): List<Sheet>
}

/** The formats a sheet is held to; how many cells it may hold is a Tunable. */
object Sheets {
    const val NAME_LENGTH = 200

    /** What a tab may be called: Excel's own limit, so an export never has to shorten one. */
    const val TAB_NAME_LENGTH = 31

    /** Tabs in one workbook. */
    const val TABS_MOST = 50

    /** What one cell may hold, typed or formula: Excel's own limit. */
    const val CELL_LENGTH = 32_767

    /** The last column and row Excel has, XFD and 1,048,576, so whatever is here can be exported. */
    const val COLUMNS_MOST = 16_384
    const val ROWS_MOST = 1_048_576

    /** A column narrower than this cannot be clicked, and one wider than this is not a column. */
    const val WIDTH_LEAST = 16
    const val WIDTH_MOST = 2_000

    /** What a new sheet's tab is called, and the stem a tab added without a name is numbered from. */
    const val FIRST_TAB = "Sheet1"

    /** The characters Excel refuses in a tab's name; refused here so an export never meets one. */
    const val TAB_NAME_REFUSED = "[]:*?/\\"
}

/** One tab of a workbook: what was typed, by cell (`B4`), and the widths given to columns, by letter. */
data class SheetTab(
    val name: String,
    val cells: Map<String, String>,
    val widths: Map<String, Int> = emptyMap(),
)

/** A cell address taken apart: column and row from 0. */
data class SheetCellAt(val column: Int, val row: Int) {
    val name: String get() = columnName(column) + (row + 1)

    companion object {
        private val SHAPE = Regex("^([A-Za-z]{1,3})([1-9][0-9]{0,6})$")

        /** `b4` and `B4` are both B4; anything else is null. */
        fun of(said: String?): SheetCellAt? {
            val match = SHAPE.matchEntire(said?.trim().orEmpty()) ?: return null
            val column = columnOf(match.groupValues[1]) ?: return null
            val row = match.groupValues[2].toInt() - 1
            return if (row < Sheets.ROWS_MOST) SheetCellAt(column, row) else null
        }

        /** `A` is 0, `Z` 25, `AA` 26; past XFD, null. */
        fun columnOf(letters: String): Int? {
            if (letters.isEmpty() || letters.length > 3 || !letters.all { it.isLetter() }) return null
            val column = letters.uppercase().fold(0) { sum, letter -> sum * 26 + (letter - 'A' + 1) } - 1
            return column.takeIf { it < Sheets.COLUMNS_MOST }
        }

        fun columnName(column: Int): String {
            var left = column + 1
            val letters = StringBuilder()
            while (left > 0) {
                val at = (left - 1) % 26
                letters.append('A' + at)
                left = (left - 1) / 26
            }
            return letters.reverse().toString()
        }
    }
}

/**
 * Reads, checks and writes a sheet's workbook.
 *
 * Everything that reaches the table comes through [read], from the editor, an
 * import and a model alike, so the three cannot come to store different shapes.
 * What it refuses it says, tab by tab and cell by cell, because the reader of
 * the refusal is as often a model as a person and a model can only correct what
 * it is told. An empty cell is not kept: a cell given "" is a cell cleared.
 */
class SheetWorkbook(private val mapper: ObjectMapper) {

    /** The tabs of a workbook as it is stored or as sent; an empty text is one empty tab. */
    fun read(workbook: String?): List<SheetTab> {
        if (workbook.isNullOrBlank()) return listOf(SheetTab(Sheets.FIRST_TAB, emptyMap()))
        val tree = try {
            mapper.readTree(workbook)
        } catch (_: Exception) {
            throw SheetWorkbookInvalidException("it is not JSON")
        }
        val list = when {
            tree.isObject && tree.path("tabs").isArray -> tree.path("tabs")
            tree.isArray -> tree
            else -> throw SheetWorkbookInvalidException("tabs has to be a list")
        }
        if (list.isEmpty) throw SheetWorkbookInvalidException("a workbook needs at least one tab")
        if (list.size() > Sheets.TABS_MOST) throw SheetWorkbookInvalidException("a workbook holds at most ${Sheets.TABS_MOST} tabs")
        val taken = mutableSetOf<String>()
        return (0 until list.size()).map { at ->
            tab(list.get(at), at, taken).also { taken += it.name.lowercase() }
        }
    }

    /** The workbook as it is stored. */
    fun write(tabs: List<SheetTab>): String = mapper.writeValueAsString(mapOf("tabs" to tabs.map(::shown)))

    /** One tab as a map, the way the workbook and the tools both give it, cells in reading order. */
    fun shown(tab: SheetTab): Map<String, Any?> = buildMap {
        put("name", tab.name)
        put("cells", ordered(tab.cells))
        if (tab.widths.isNotEmpty()) put("widths", tab.widths.toSortedMap(compareBy({ it.length }, { it })))
    }

    /**
     * A tab's name checked against the others in the workbook, which [taken]
     * holds lower-cased: Excel tells two tabs apart without regard to case, and
     * a formula naming one would otherwise mean whichever came first.
     */
    fun tabName(said: String?, taken: Set<String>, at: Int): String {
        val name = said?.trim().orEmpty()
        if (name.isEmpty()) throw SheetWorkbookInvalidException("tab ${at + 1} needs a name")
        if (name.length > Sheets.TAB_NAME_LENGTH) {
            throw SheetWorkbookInvalidException("tab $name has a name longer than ${Sheets.TAB_NAME_LENGTH} characters")
        }
        if (name.any { it in Sheets.TAB_NAME_REFUSED } || name.startsWith("'") || name.endsWith("'")) {
            throw SheetWorkbookInvalidException("tab $name has a name with one of ${Sheets.TAB_NAME_REFUSED} in it, or starts or ends with an apostrophe")
        }
        if (name.lowercase() in taken) throw SheetWorkbookInvalidException("two tabs are called $name")
        return name
    }

    /** A cell's address and what it holds, checked; null where it holds nothing and is therefore not kept. */
    fun cell(address: String, value: String?, tab: String): Pair<String, String>? {
        val at = SheetCellAt.of(address)
            ?: throw SheetWorkbookInvalidException("$address in tab $tab is not a cell; a cell is a column and a row, such as B4")
        if (value.isNullOrEmpty()) return null
        if (value.length > Sheets.CELL_LENGTH) {
            throw SheetWorkbookInvalidException("${at.name} in tab $tab holds more than ${Sheets.CELL_LENGTH} characters")
        }
        return at.name to value
    }

    private fun tab(node: JsonNode, at: Int, taken: Set<String>): SheetTab {
        if (!node.isObject) throw SheetWorkbookInvalidException("tab ${at + 1} is not an object")
        val name = tabName(text(node.path("name")), taken, at)
        val cells = node.path("cells")
        if (!cells.isMissingNode && !cells.isNull && !cells.isObject) {
            throw SheetWorkbookInvalidException("the cells of tab $name have to be an object, from address to what it holds")
        }
        val held = linkedMapOf<String, String>()
        if (cells.isObject) {
            cells.properties().forEach { (address, value) ->
                val said = when {
                    value.isNull -> null
                    value.isString -> value.stringValue()
                    value.isNumber || value.isBoolean -> value.asText().let { if (value.isBoolean) it.uppercase() else it }
                    else -> throw SheetWorkbookInvalidException("$address in tab $name has to hold text or a number")
                }
                cell(address, said, name)?.let { (cellName, kept) ->
                    if (cellName in held) throw SheetWorkbookInvalidException("tab $name names $cellName twice")
                    held[cellName] = kept
                }
            }
        }
        return SheetTab(name = name, cells = held, widths = widths(node.path("widths"), name))
    }

    private fun widths(node: JsonNode, tab: String): Map<String, Int> {
        if (node.isMissingNode || node.isNull) return emptyMap()
        if (!node.isObject) throw SheetWorkbookInvalidException("the widths of tab $tab have to be an object, from column to pixels")
        return buildMap {
            node.properties().forEach { (letters, width) ->
                val column = SheetCellAt.columnOf(letters.trim())
                    ?: throw SheetWorkbookInvalidException("$letters in the widths of tab $tab is not a column")
                if (!width.isNumber) throw SheetWorkbookInvalidException("the width of column $letters in tab $tab has to be a number")
                put(SheetCellAt.columnName(column), width.asDouble().toInt().coerceIn(Sheets.WIDTH_LEAST, Sheets.WIDTH_MOST))
            }
        }
    }

    private fun text(node: JsonNode): String? = when {
        node.isString -> node.stringValue()
        node.isNumber -> node.asText()
        else -> null
    }

    companion object {
        /** Cells row by row and, within a row, column by column - how a person reads a sheet, and how a model is handed one. */
        fun ordered(cells: Map<String, String>): Map<String, String> =
            cells.entries
                .sortedWith(compareBy({ SheetCellAt.of(it.key)?.row ?: 0 }, { SheetCellAt.of(it.key)?.column ?: 0 }))
                .associateTo(linkedMapOf()) { it.key to it.value }
    }
}

class SheetNotFoundException(val id: Long) : RuntimeException("No sheet with id $id"), Refusal {
    override val arguments get() = mapOf("id" to id)
}

class SheetNameInvalidException :
    RuntimeException("A sheet needs a name, ${Sheets.NAME_LENGTH} characters at most")

class SheetWorkbookInvalidException(val why: String) :
    RuntimeException("The sheet cannot be saved: $why"), Refusal {
    override val arguments get() = mapOf("why" to why)
}

class SheetTooLargeException(val count: Int, val most: Int) :
    RuntimeException("A sheet holds at most $most filled cells, and this one would hold $count"), Refusal {
    override val arguments get() = mapOf("count" to count, "most" to most)
}

class SheetChangedException(val name: String) :
    RuntimeException("Sheet $name was changed since it was opened; open it again before saving"), Refusal {
    override val arguments get() = mapOf("name" to name)
}

class SheetAnswerFailedException(val reason: String) :
    RuntimeException("The model could not answer: $reason"), Refusal {
    override val arguments get() = mapOf("reason" to reason)
}

/** A file brought in as a sheet that is not one of the kinds a sheet is made from, or cannot be read as one. */
class SheetImportRefusedException(val reason: String) :
    RuntimeException("That file cannot be made into a sheet: $reason"), Refusal {
    override val arguments get() = mapOf("reason" to reason)
}

/** An export asked for in a format a sheet is not written in. */
class SheetFormatUnknownException(val asked: String) :
    RuntimeException("A sheet is exported as xlsx or csv, not $asked"), Refusal {
    override val arguments get() = mapOf("asked" to asked)
}
