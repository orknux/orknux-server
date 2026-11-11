package io.mszymanski.orknux.server.mcp

import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.design.SheetAPI
import io.mszymanski.orknux.server.design.SheetCellAt
import io.mszymanski.orknux.server.design.SheetCellFormat
import io.mszymanski.orknux.server.design.SheetEngine
import io.mszymanski.orknux.server.design.SheetFormats
import io.mszymanski.orknux.server.design.SheetRepository
import io.mszymanski.orknux.server.design.SheetTab
import io.mszymanski.orknux.server.design.SheetView
import io.mszymanski.orknux.server.design.SheetWorkbook
import io.mszymanski.orknux.server.design.SheetWorkbookInvalidException
import io.mszymanski.orknux.server.design.Sheets
import io.mszymanski.orknux.server.security.InstallationAddress
import io.mszymanski.orknux.server.security.WebProperties
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * The orknux_* tools that read and fill in sheets.
 *
 * Offered wherever the rest of the surface is - an agent with Orknux access, an
 * MCP client, and the chat in the sheet editor, which offers the ones that work
 * in the sheet it has open. Every write goes through [SheetAPI], so what a model
 * writes is checked, counted and audited as the editor's own save is, and the
 * editor shows it the moment it is taken up.
 *
 * A cell is answered with what it comes to beside what was typed, worked out by
 * the same [SheetEngine] the editor draws from, so a model quoting a total
 * quotes the one the person sees.
 */
@Service
class SheetTools(
    private val sheets: SheetRepository,
    /** Lazy: the API holds the editor's chat, which reaches these tools through OrknuxTools. */
    @param:Lazy private val api: SheetAPI,
    private val web: WebProperties,
    private val address: InstallationAddress,
    private val mapper: ObjectMapper,
    private val tuning: Tuning = Tuning.DEFAULTS,
) {

    private val workbook = SheetWorkbook(mapper)

    fun specs(scope: OrknuxScope): List<ToolSpec> = buildList {
        add(
            ToolSpec(
                name = LIST,
                description = "The sheets in this workspace - spreadsheets kept in Design - the one worked on last first: " +
                    "id, name, its tabs, how many cells are filled, and where each opens.",
                parameters = emptyList(),
                summary = "Lists the workspace's sheets (spreadsheets)",
            ),
        )
        add(
            ToolSpec(
                name = READ,
                description = "One sheet's cells, tab by tab, row by row: each filled cell with what it shows and, for a " +
                    "formula or anything else not shown as typed, what was typed (`typed`), and its formatting " +
                    "(`format`) where it has any. Errors such as #DIV/0! are " +
                    "answered as they are shown, with the reason where there is one. Narrow it to a tab and a range " +
                    "such as A1:F40 for a large sheet. Answers links to download it as .xlsx and each tab as .csv.",
                parameters = listOf(
                    ToolParameterSpec(SHEET, "The sheet's name, or its id.", required = true),
                    ToolParameterSpec(TAB, "Only this tab, by name; every tab when left out.", required = false),
                    ToolParameterSpec(RANGE, "Only these cells, such as A1:F40 or C:C; all of them when left out.", required = false),
                ),
                summary = "Reads one sheet's cells and values",
            ),
        )
        if (!scope.mayWrite) return@buildList
        add(
            ToolSpec(
                name = CREATE,
                description = "Makes a sheet, empty or filled. $CELLS_SAID $ANSWERED",
                parameters = listOf(
                    ToolParameterSpec("name", "What it is called.", required = true),
                    ToolParameterSpec(
                        TABS,
                        "Its tabs, in order: [{\"name\": \"Budget\", \"cells\": {\"A1\": \"Item\", \"B2\": \"=SUM(B3:B9)\"}, " +
                            "\"formats\": {\"A1\": {\"bold\": true}}}]. formats is optional, by cell; $FORMAT_SAID " +
                            "One empty tab called ${Sheets.FIRST_TAB} when left out.",
                        required = false,
                        TAB_LIST,
                    ),
                ),
                summary = "Makes a sheet (spreadsheet)",
            ),
        )
        add(
            ToolSpec(
                name = WRITE,
                description = "Writes cells in one tab of a sheet, keeping every cell not named. Give $CELLS by address, " +
                    "or $ROWS - a grid of rows written from $AT down and to the right, for a table - or $FORMATS to " +
                    "format cells, or any of them at once. $CELLS_SAID " +
                    "An empty string clears a cell; its formatting stays. $ANSWERED",
                parameters = listOf(
                    ToolParameterSpec(SHEET, "The sheet's name, or its id.", required = true),
                    ToolParameterSpec(TAB, "The tab, by name; the first when left out.", required = false),
                    ToolParameterSpec(CELLS, "What to write, by address: {\"A1\": \"Total\", \"B1\": \"=SUM(B2:B9)\"}.", required = false, CELL_MAP),
                    ToolParameterSpec(ROWS, "Rows of cells to write as a block: [[\"Item\", \"Cost\"], [\"Rent\", \"1200\"]].", required = false, ROW_LIST),
                    ToolParameterSpec(AT, "Where $ROWS starts, its top left cell; A1 when left out.", required = false),
                    ToolParameterSpec(
                        FORMATS,
                        "How to format cells, by cell or range: {\"A1:D1\": {\"bold\": true, \"fill\": \"#fff2cc\"}, " +
                            "\"D2:D9\": {\"number\": \"currency\", \"currency\": \"zł\"}}. Only the keys given change; a key " +
                            "given null or false is taken away, and a range given null loses all its formatting. $FORMAT_SAID",
                        required = false,
                        FORMAT_MAP,
                    ),
                ),
                summary = "Writes cells in a sheet",
            ),
        )
        add(
            ToolSpec(
                name = TABS_TOOL,
                description = "Adds, renames and removes a sheet's tabs, and sets how wide its columns are. A tab removed " +
                    "takes its cells with it. A formula naming a renamed tab is not rewritten: write it again. $ANSWERED",
                parameters = listOf(
                    ToolParameterSpec(SHEET, "The sheet's name, or its id.", required = true),
                    ToolParameterSpec(ADD, "Names of tabs to add at the end.", required = false, STRING_LIST),
                    ToolParameterSpec(RENAME, "Tabs to rename: [{\"from\": \"Sheet1\", \"to\": \"Budget\"}].", required = false, RENAME_LIST),
                    ToolParameterSpec(REMOVE, "Names of tabs to remove; a sheet keeps at least one.", required = false, STRING_LIST),
                    ToolParameterSpec(
                        WIDTHS,
                        "Column widths in pixels for one tab, by letter: {\"tab\": \"Budget\", \"A\": 220}. The first tab when it names none.",
                        required = false,
                        mapOf("type" to "object"),
                    ),
                ),
                summary = "Adds, renames and removes a sheet's tabs",
            ),
        )
    }

    fun handles(name: String): Boolean = name in NAMES

    fun run(scope: OrknuxScope, name: String, arguments: String): String {
        val asked = runCatching { mapper.readTree(arguments.ifBlank { "{}" }) }.getOrNull()
            ?: return refuse("The arguments are not JSON")
        return try {
            when (name) {
                LIST -> list(scope)
                READ -> withSheet(scope, asked) { read(it, asked) }
                else -> if (!scope.mayWrite) refuse("This conversation may read what is here, but not change it") else when (name) {
                    CREATE -> create(scope, asked)
                    WRITE -> withSheet(scope, asked) { write(it, asked) }
                    TABS_TOOL -> withSheet(scope, asked) { tabs(it, asked) }
                    else -> refuse("There is no tool called $name")
                }
            }
        } catch (refused: SheetWorkbookInvalidException) {
            refuse(refused.message ?: "That cannot be written")
        }
    }

    private fun list(scope: OrknuxScope): String = mapper.writeValueAsString(
        mapOf(
            "sheets" to sheets.findByWorkspaceIdOrderByUpdatedAtDescIdDesc(scope.workspaceId).map { held ->
                val view = api.view(held)
                mapOf(
                    "id" to view.id,
                    "name" to view.name,
                    "tabs" to runCatching { workbook.read(view.workbook).map { it.name } }.getOrDefault(emptyList()),
                    "cells" to view.cellCount,
                    "updatedAt" to view.updatedAt,
                    "url" to link(view),
                )
            },
        ),
    )

    /**
     * The cells asked for, held to what a model is handed of a sheet at once -
     * the attached-sheet reader's number, since it is the same reader paying
     * for the same characters. Past it the answer stops at a whole cell and
     * says where, so the next read can ask for the rest by range.
     */
    private fun read(held: SheetView, asked: JsonNode): String {
        val tabs = workbook.read(held.workbook)
        val only = text(asked.path(TAB))
        if (only != null && tabs.none { it.name.equals(only, ignoreCase = true) }) {
            return refuse("${held.name} has no tab $only; it has ${tabs.joinToString(", ") { it.name }}")
        }
        val range = text(asked.path(RANGE))?.let { Range.of(it) ?: return refuse("$it is not a range; a range is like A1:F40, B:B or 3:3") }
        val values = api.engine.values(tabs)
        val most = tuning[Tunable.SHEET_ANSWER_CHARACTERS]
        var spent = 0
        var stoppedAt: String? = null
        val shown = tabs.mapIndexedNotNull { index, tab ->
            if (only != null && !tab.name.equals(only, ignoreCase = true)) return@mapIndexedNotNull null
            val worked = values[index].second
            val cells = linkedMapOf<String, Any>()
            for ((address, typed) in SheetWorkbook.ordered(tab.cells)) {
                if (stoppedAt != null) break
                val at = requireNotNull(SheetCellAt.of(address))
                if (range != null && !range.holds(at)) continue
                val value = worked[address] ?: continue
                val cell = buildMap {
                    put("shows", value.shown)
                    if (typed != value.shown) put("typed", typed)
                    if (value.type == "e") value.message?.let { put("why", it) }
                    tab.formats[address]?.let { put("format", it.asMap()) }
                }
                val size = address.length + cell.entries.sumOf { it.key.length + it.value.toString().length + 6 }
                if (spent + size > most && cells.isNotEmpty()) {
                    stoppedAt = "${tab.name}!$address"
                    break
                }
                spent += size
                cells[address] = cell
            }
            mapOf("name" to tab.name, "index" to index, "cells" to cells, "csv" to csvLink(held, index))
        }
        return mapper.writeValueAsString(
            buildMap {
                put("id", held.id)
                put("name", held.name)
                put("revision", held.revision)
                put("tabs", shown)
                stoppedAt?.let {
                    put("truncated", "The answer stopped before $it to stay short; read the rest with $RANGE.")
                }
                put("xlsx", base() + "/api/sheets/${held.id}/export/xlsx")
                put("url", link(held))
            },
        )
    }

    private fun create(scope: OrknuxScope, asked: JsonNode): String {
        val name = text(asked.path("name")) ?: return refuse("What is it called? Give it a name.")
        val tabs = node(asked.path(TABS))?.takeIf { !it.isEmpty }
        val made = api.createSheet(scope.workspaceId, name, tabs?.let { mapper.writeValueAsString(mapOf("tabs" to it)) })
        return written(made, emptySet())
    }

    private fun write(held: SheetView, asked: JsonNode): String {
        val tabs = workbook.read(held.workbook)
        val index = tabIndex(tabs, text(asked.path(TAB))) ?: return refuse(noTab(held, tabs, text(asked.path(TAB))))
        val tab = tabs[index]
        val writes = linkedMapOf<String, String>()
        node(asked.path(CELLS))?.let { given ->
            if (!given.isObject) return refuse("Give $CELLS as an object, from address to what it holds")
            given.properties().forEach { (cellAddress, value) -> writes[cellAddress] = said(value) }
        }
        node(asked.path(ROWS))?.let { given ->
            if (!given.isArray) return refuse("Give $ROWS as a list of rows, each a list of cells")
            val start = text(asked.path(AT))?.let { SheetCellAt.of(it) ?: return refuse("$it is not a cell; $AT is one such as B4") } ?: SheetCellAt(0, 0)
            for (r in 0 until given.size()) {
                val row = given.get(r)
                if (!row.isArray) return refuse("Row ${r + 1} of $ROWS is not a list of cells")
                for (c in 0 until row.size()) {
                    if (start.column + c >= Sheets.COLUMNS_MOST || start.row + r >= Sheets.ROWS_MOST) {
                        return refuse("$ROWS runs past the last column or row a sheet has")
                    }
                    writes[SheetCellAt(start.column + c, start.row + r).name] = said(row.get(c))
                }
            }
        }
        val formatting = node(asked.path(FORMATS))?.let { given ->
            if (!given.isObject) return refuse("Give $FORMATS as an object, from a cell or range to its formatting")
            formatted(tab, given) ?: return refuse(tooWide(given))
        }
        if (writes.isEmpty() && formatting == null) return refuse("Nothing to write: give $CELLS, $ROWS or $FORMATS")
        val cells = LinkedHashMap(tab.cells)
        val touched = mutableSetOf<String>()
        writes.forEach { (cellAddress, value) ->
            val at = SheetCellAt.of(cellAddress)
                ?: throw SheetWorkbookInvalidException("$cellAddress is not a cell; a cell is a column and a row, such as B4")
            cells.remove(at.name)
            workbook.cell(at.name, value, tab.name)?.let { (name, kept) -> cells[name] = kept }
            touched += "${index}!${at.name}"
        }
        val changed = tabs.toMutableList().also { it[index] = tab.copy(cells = cells, formats = formatting ?: tab.formats) }
        return written(api.saveTabs(held.id, changed), touched)
    }

    /**
     * A tab's formatting with [given] applied, range by range in the order
     * given; null where a range is wider than a sheet may hold formatting for,
     * which is said before anything is worked out cell by cell.
     */
    private fun formatted(tab: SheetTab, given: JsonNode): Map<String, SheetCellFormat>? {
        val most = tuning[Tunable.SHEET_CELLS_MOST]
        val formats = LinkedHashMap(tab.formats)
        for ((said, format) in given.properties()) {
            val range = Range.of(said)
                ?: throw SheetWorkbookInvalidException("$said in $FORMATS is not a cell or a range; a range is like A1:D9")
            if (range.size > most) return null
            for (row in range.rows) {
                for (column in range.columns) {
                    val name = SheetCellAt(column, row).name
                    val now = if (format.isNull) SheetCellFormat.NONE else SheetFormats.patch(formats[name] ?: SheetCellFormat.NONE, format, "$name in tab ${tab.name}")
                    if (now.isEmpty) formats.remove(name) else formats[name] = now
                }
            }
        }
        return formats
    }

    private fun tooWide(given: JsonNode): String =
        "A range in $FORMATS covers more cells than a sheet holds formatting for (${tuning[Tunable.SHEET_CELLS_MOST]}); " +
            "format the cells that are used: ${given.propertyNames().joinToString(", ")}"

    private fun tabs(held: SheetView, asked: JsonNode): String {
        val tabs = workbook.read(held.workbook).toMutableList()
        var changed = false
        strings(asked.path(REMOVE)).forEach { gone ->
            val at = tabIndex(tabs, gone) ?: return refuse(noTab(held, tabs, gone))
            if (tabs.size == 1) return refuse("A sheet keeps at least one tab; $gone is its last")
            tabs.removeAt(at)
            changed = true
        }
        node(asked.path(RENAME))?.let { given ->
            if (!given.isArray) return refuse("Give $RENAME as a list of {from, to}")
            given.forEach { change ->
                val from = text(change.path("from")) ?: return refuse("Each tab in $RENAME needs from, the name it has")
                val at = tabIndex(tabs, from) ?: return refuse(noTab(held, tabs, from))
                val others = tabs.filterIndexed { i, _ -> i != at }.map { it.name.lowercase() }.toSet()
                tabs[at] = tabs[at].copy(name = workbook.tabName(text(change.path("to")), others, at))
                changed = true
            }
        }
        strings(asked.path(ADD)).forEach { added ->
            if (tabs.size >= Sheets.TABS_MOST) return refuse("A sheet holds at most ${Sheets.TABS_MOST} tabs")
            tabs += SheetTab(workbook.tabName(added, tabs.map { it.name.lowercase() }.toSet(), tabs.size), emptyMap())
            changed = true
        }
        node(asked.path(WIDTHS))?.let { given ->
            if (!given.isObject) return refuse("Give $WIDTHS as an object, from column letter to pixels")
            val at = tabIndex(tabs, text(given.path(TAB))) ?: return refuse(noTab(held, tabs, text(given.path(TAB))))
            val widths = LinkedHashMap(tabs[at].widths)
            given.properties().filter { it.key != TAB }.forEach { (letters, width) ->
                val column = SheetCellAt.columnOf(letters.trim()) ?: return refuse("$letters is not a column")
                if (!width.isNumber) return refuse("The width of column $letters has to be a number of pixels")
                widths[SheetCellAt.columnName(column)] = width.asDouble().toInt().coerceIn(Sheets.WIDTH_LEAST, Sheets.WIDTH_MOST)
            }
            tabs[at] = tabs[at].copy(widths = widths)
            changed = true
        }
        if (!changed) return refuse("Nothing to change: give $ADD, $RENAME, $REMOVE or $WIDTHS")
        return written(api.saveTabs(held.id, tabs), emptySet())
    }

    /**
     * What a write answers: the sheet's tabs, its url, and what each cell
     * written comes to now - a model that wrote a formula learns whether it
     * worked from this, not from a read it might not make.
     */
    private fun written(view: SheetView, touched: Set<String>): String {
        val tabs = workbook.read(view.workbook)
        val values = if (touched.isEmpty()) emptyList() else api.engine.values(tabs)
        val cells = touched.mapNotNull { key ->
            val (index, cellAddress) = key.split('!', limit = 2).let { it[0].toInt() to it[1] }
            val value = values.getOrNull(index)?.second?.get(cellAddress) ?: return@mapNotNull null
            cellAddress to value
        }
        return mapper.writeValueAsString(
            buildMap {
                put("id", view.id)
                put("name", view.name)
                put("revision", view.revision)
                put("tabs", tabs.map { it.name })
                put("cells", view.cellCount)
                if (cells.isNotEmpty()) {
                    put("wrote", cells.associate { (cellAddress, value) -> cellAddress to value.shown })
                    val wrong = cells.filter { it.second.type == "e" }
                    if (wrong.isNotEmpty()) {
                        put("errors", wrong.associate { (cellAddress, value) -> cellAddress to listOfNotNull(value.shown, value.message).joinToString(": ") })
                    }
                }
                put("url", link(view))
            },
        )
    }

    private fun withSheet(scope: OrknuxScope, asked: JsonNode, then: (SheetView) -> String): String {
        val named = text(asked.path(SHEET)) ?: return refuse("Which sheet? Give its name or id; $LIST lists them.")
        val matches = sheets.findByWorkspaceIdOrderByUpdatedAtDescIdDesc(scope.workspaceId)
            .filter { it.name.equals(named, ignoreCase = true) || it.id?.toString() == named }
        return when {
            matches.isEmpty() -> refuse("There is no sheet called $named here; $LIST lists them")
            matches.size > 1 -> refuse("More than one sheet is called $named; use its id")
            else -> then(api.view(matches.single()))
        }
    }

    private fun tabIndex(tabs: List<SheetTab>, named: String?): Int? =
        if (named == null) 0 else tabs.indexOfFirst { it.name.equals(named, ignoreCase = true) }.takeIf { it >= 0 }

    private fun noTab(held: SheetView, tabs: List<SheetTab>, named: String?) =
        "${held.name} has no tab $named; it has ${tabs.joinToString(", ") { it.name }}"

    /** A cell's value as a model sends it: text, a number or a boolean, null clearing it. */
    private fun said(node: JsonNode): String = when {
        node.isNull || node.isMissingNode -> ""
        node.isString -> node.stringValue()
        node.isBoolean -> if (node.booleanValue()) "TRUE" else "FALSE"
        node.isNumber -> node.asText()
        else -> throw SheetWorkbookInvalidException("a cell holds text or a number, not ${node.nodeType.name.lowercase()}")
    }

    /** An argument as given, or as the text of one - a model will send either, and an MCP client always sends text. */
    private fun node(node: JsonNode): JsonNode? = when {
        node.isMissingNode || node.isNull -> null
        node.isString -> node.stringValue().trim().let { said ->
            if (said.isEmpty()) null else runCatching { mapper.readTree(said) }.getOrNull()
                ?: throw SheetWorkbookInvalidException("JSON was expected and got text")
        }
        else -> node
    }

    private fun strings(node: JsonNode): List<String> {
        val given = node(node) ?: return emptyList()
        return if (given.isArray) (0 until given.size()).mapNotNull { text(given.get(it)) } else listOfNotNull(text(given))
    }

    private fun text(node: JsonNode): String? = when {
        node.isString -> node.stringValue()?.trim()?.takeIf { it.isNotEmpty() }
        node.isNumber -> node.asText()
        else -> null
    }

    private fun link(view: SheetView): String = base() + "/workspace/${view.workspaceId}/design/sheets/${view.id}"

    private fun csvLink(view: SheetView, tab: Int): String = base() + "/api/sheets/${view.id}/export/csv?tab=$tab"

    private fun base(): String = address.base().ifEmpty { web.allowedOrigins.firstOrNull()?.trimEnd('/').orEmpty() }

    private fun refuse(says: String): String = mapper.writeValueAsString(mapOf("error" to says))

    /** A block of cells - A1:F40, a whole column C:C or a whole row 3:3 - or one cell. Bounds are taken in either order. */
    private data class Range(val columns: IntRange, val rows: IntRange) {
        fun holds(at: SheetCellAt) = at.column in columns && at.row in rows

        /** How many cells it covers: a whole column is a million. */
        val size: Long get() = (columns.last - columns.first + 1).toLong() * (rows.last - rows.first + 1)

        companion object {
            private val COLUMNS = Regex("^([A-Za-z]{1,3}):([A-Za-z]{1,3})$")
            private val ROWS = Regex("^([0-9]{1,7}):([0-9]{1,7})$")

            fun of(said: String): Range? {
                val trimmed = said.trim().substringAfter('!')
                COLUMNS.matchEntire(trimmed)?.let { match ->
                    val a = SheetCellAt.columnOf(match.groupValues[1]) ?: return null
                    val b = SheetCellAt.columnOf(match.groupValues[2]) ?: return null
                    return Range(minOf(a, b)..maxOf(a, b), 0 until Sheets.ROWS_MOST)
                }
                ROWS.matchEntire(trimmed)?.let { match ->
                    val a = match.groupValues[1].toInt() - 1
                    val b = match.groupValues[2].toInt() - 1
                    if (a < 0 || b < 0) return null
                    return Range(0 until Sheets.COLUMNS_MOST, minOf(a, b)..maxOf(a, b))
                }
                val ends = trimmed.split(':')
                if (ends.size > 2) return null
                val a = SheetCellAt.of(ends[0]) ?: return null
                val b = SheetCellAt.of(ends.getOrElse(1) { ends[0] }) ?: return null
                return Range(minOf(a.column, b.column)..maxOf(a.column, b.column), minOf(a.row, b.row)..maxOf(a.row, b.row))
            }
        }
    }

    companion object {
        const val LIST = "orknux_sheets"
        const val READ = "orknux_sheet"
        const val CREATE = "orknux_create_sheet"
        const val WRITE = "orknux_write_sheet_cells"
        const val TABS_TOOL = "orknux_change_sheet_tabs"

        /** What the editor's own chat is offered: working in the sheet it has open. */
        val EDITING = setOf(READ, WRITE, TABS_TOOL)

        val NAMES = setOf(LIST, READ, CREATE, WRITE, TABS_TOOL)

        private const val SHEET = "sheet"
        private const val TAB = "tab"
        private const val TABS = "tabs"
        private const val RANGE = "range"
        private const val CELLS = "cells"
        private const val ROWS = "rows"
        private const val AT = "at"
        private const val ADD = "add"
        private const val RENAME = "rename"
        private const val REMOVE = "remove"
        private const val WIDTHS = "widths"
        private const val FORMATS = "formats"

        /** What a cell's formatting may hold, said wherever a model sets one. */
        private val FORMAT_SAID =
            "A cell's formatting holds any of: bold, italic, underline, strike and wrap (true or false); color and fill " +
                "(#rrggbb, the words and the cell behind them); align (${SheetFormats.ALIGNS.joinToString(", ")}); " +
                "number (${SheetFormats.NUMBERS.joinToString(", ")} or general), which changes only how a number is shown, " +
                "never what it is - write 0.25 and format it as percent to show 25%; decimals (places after the point); " +
                "currency (its symbol, such as zł or €; \$ when left out); border (all, none, or a list of the sides " +
                "to draw a line along: ${SheetFormats.SIDES.joinToString(", ")} - an outline round a range is its edge " +
                "cells' outer sides); borderColor (#rrggbb, black when left out); and ungrouped (true to write a number " +
                "or currency with no comma between thousands, as 2024 rather than 2,024)."

        private val FORMAT: Map<String, Any?> = mapOf(
            "type" to listOf("object", "null"),
            "properties" to mapOf(
                "bold" to mapOf("type" to "boolean"),
                "italic" to mapOf("type" to "boolean"),
                "underline" to mapOf("type" to "boolean"),
                "strike" to mapOf("type" to "boolean"),
                "wrap" to mapOf("type" to "boolean"),
                "color" to mapOf("type" to "string"),
                "fill" to mapOf("type" to "string"),
                "align" to mapOf("type" to "string", "enum" to SheetFormats.ALIGNS),
                "number" to mapOf("type" to "string", "enum" to SheetFormats.NUMBERS + "general"),
                "decimals" to mapOf("type" to "integer"),
                "currency" to mapOf("type" to "string"),
                "border" to mapOf(
                    "type" to listOf("array", "string"),
                    "items" to mapOf("type" to "string", "enum" to SheetFormats.SIDES),
                ),
                "borderColor" to mapOf("type" to "string"),
                "ungrouped" to mapOf("type" to "boolean"),
            ),
        )

        private val FORMAT_MAP: Map<String, Any?> = mapOf("type" to "object", "additionalProperties" to FORMAT)

        /** How a cell is written, said in every tool that writes one so a model reads it where it writes. */
        private const val CELLS_SAID =
            "A cell is addressed by column and row (B4) and holds what a person would type: a number (12.5, never " +
                "12,50 or 12%), text, TRUE or FALSE, or a formula starting with = such as =SUM(B2:B9) or " +
                "='Other tab'!A1*2, in Excel's function names with commas between arguments. Start text with an " +
                "apostrophe to keep it as typed: '0012."

        private const val ANSWERED =
            "Answers the sheet with its url - where it opens in the editor, to give the person - and what each cell " +
                "written now shows, with any that came to an error."

        private val STRING_LIST: Map<String, Any?> = mapOf("type" to "array", "items" to mapOf("type" to "string"))

        private val CELL_MAP: Map<String, Any?> = mapOf(
            "type" to "object",
            "additionalProperties" to mapOf("type" to listOf("string", "number", "boolean")),
        )

        private val ROW_LIST: Map<String, Any?> = mapOf(
            "type" to "array",
            "items" to mapOf("type" to "array", "items" to mapOf("type" to listOf("string", "number", "boolean"))),
        )

        private val TAB_LIST: Map<String, Any?> = mapOf(
            "type" to "array",
            "items" to mapOf(
                "type" to "object",
                "properties" to mapOf("name" to mapOf("type" to "string"), "cells" to CELL_MAP, "formats" to FORMAT_MAP),
            ),
        )

        private val RENAME_LIST: Map<String, Any?> = mapOf(
            "type" to "array",
            "items" to mapOf(
                "type" to "object",
                "properties" to mapOf("from" to mapOf("type" to "string"), "to" to mapOf("type" to "string")),
            ),
        )
    }
}
