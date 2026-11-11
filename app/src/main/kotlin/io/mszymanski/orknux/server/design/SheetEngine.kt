package io.mszymanski.orknux.server.design

import io.mszymanski.orknux.server.embedded.SheetReader
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import org.apache.poi.ss.formula.FormulaParseException
import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.Font
import org.apache.poi.ss.usermodel.FormulaError
import org.apache.poi.ss.usermodel.FormulaEvaluator
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFColor
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayOutputStream
import java.io.StringWriter
import java.math.BigDecimal
import java.math.MathContext
import java.util.Locale

/**
 * What a sheet's cells come to, and the sheet as a file.
 *
 * **One engine, and it is POI's.** The editor shows values the server worked
 * out, the orknux_* tools hand a model the same values, and the .xlsx export
 * carries the same formulas for Excel to work out again - so a formula is
 * read by one parser and one set of functions everywhere it is read. A second
 * engine in the browser would have been two products that disagree about
 * `=ROUND(2.5)`, and the reader would find out from a model quoting the other.
 * A function POI does not have is answered as `#NAME?` with the reason beside
 * it, never as a number somebody made up.
 *
 * What was typed is read the way a spreadsheet reads it: `=` starts a formula,
 * TRUE and FALSE are booleans, something shaped like a number is one, a
 * leading apostrophe keeps the rest as text (`'0012`), and anything else is
 * text.
 */
class SheetEngine {

    /** One cell's value: [type] n, s, b or e; [value] as JSON carries it; [shown] as the grid draws it. */
    data class Value(val type: String, val value: Any?, val shown: String, val message: String? = null) {
        fun asMap(): Map<String, Any?> = buildMap {
            put("t", type)
            put("v", value)
            put("f", shown)
            message?.let { put("m", it) }
        }
    }

    /** Every filled cell's value, tab by tab, in the order the tabs are in. */
    fun values(tabs: List<SheetTab>, formatted: Boolean = true): List<Pair<String, Map<String, Value>>> = built(tabs).use { (book, refused) ->
        val evaluator = book.creationHelper.createFormulaEvaluator()
        tabs.mapIndexed { index, tab ->
            val sheet = book.getSheetAt(index)
            tab.name to tab.cells.keys.associateWith { address ->
                val at = requireNotNull(SheetCellAt.of(address))
                val cell = sheet.getRow(at.row)?.getCell(at.column)
                val reason = cell?.let { refused[it] }
                when {
                    reason != null -> Value("e", "#NAME?", "#NAME?", reason)
                    cell == null -> Value("s", "", "")
                    else -> valueOf(cell, evaluator).let { if (formatted) written(it, tab.formats[address]) else it }
                }
            }
        }
    }

    /**
     * A number as its cell's format writes it - 0.25 as 25%, 1234.5 as
     * $1,234.50, 46000 as 2025-12-09 - by POI's formatter, which is the one
     * Excel's codes were made for. Only [Value.shown] changes: [Value.value]
     * stays the number, so a model reading 25% still learns it is 0.25.
     * Written in one locale whoever reads it, as the plain number always was.
     */
    private fun written(value: Value, format: SheetCellFormat?): Value {
        if (value.type != "n" || format == null) return value
        val code = SheetFormats.excelCode(format) ?: return value
        val number = (value.value as Number).toDouble()
        val shown = runCatching { FORMATTER.get().formatRawCellContents(number, CUSTOM_FORMAT, code) }.getOrNull() ?: return value
        return value.copy(shown = shown)
    }

    /** The workbook as an .xlsx, formulas and all, with the values worked out stored beside them. */
    fun xlsx(tabs: List<SheetTab>): ByteArray = built(tabs).use { (book, _) ->
        val evaluator = book.creationHelper.createFormulaEvaluator()
        book.sheetIterator().forEach { sheet ->
            sheet.forEach { row -> row.forEach { cell -> if (cell.cellType == CellType.FORMULA) runCatching { evaluator.evaluateFormulaCell(cell) } } }
        }
        ByteArrayOutputStream().also(book::write).toByteArray()
    }

    /**
     * One tab as CSV: what each cell comes to, not its formula, because a CSV
     * has nowhere to keep one. Text that a spreadsheet opening the file would
     * take for a formula is written with an apostrophe before it, so a cell
     * that says `=HYPERLINK(...)` in somebody's sheet stays words in theirs.
     */
    fun csv(tabs: List<SheetTab>, tab: Int): String {
        val chosen = tabs.getOrNull(tab) ?: tabs.first()
        // The numbers as numbers: a CSV is read by a program, and "$1,234.50" is text to one. Its format goes in an .xlsx.
        val values = values(listOf(chosen), formatted = false).single().second
        val placed = values.mapKeys { requireNotNull(SheetCellAt.of(it.key)) }
        val rows = (placed.keys.maxOfOrNull { it.row } ?: -1) + 1
        val columns = (placed.keys.maxOfOrNull { it.column } ?: -1) + 1
        val out = StringWriter()
        CSVPrinter(out, CSVFormat.RFC4180).use { printer ->
            for (row in 0 until rows) {
                printer.printRecord(
                    (0 until columns).map { column ->
                        val value = placed[SheetCellAt(column, row)] ?: return@map ""
                        if (value.type == "s" && value.shown.isNotEmpty() && value.shown[0] in CSV_FORMULA_START) "'" + value.shown else value.shown
                    },
                )
            }
        }
        return out.toString()
    }

    /**
     * A workbook read off a file: every tab, what each cell held, and its
     * formula where it had one. Text that would read as a formula or lose an
     * apostrophe here is kept as the text it was.
     */
    fun imported(reading: SheetReader.Reading, each: (String) -> SheetReader.Reading): List<SheetTab> {
        val first = when (reading) {
            is SheetReader.Reading.Read -> reading.page
            is SheetReader.Reading.Refused -> throw SheetImportRefusedException(reading.reason)
        }
        val names = first.sheets ?: listOf(Sheets.FIRST_TAB)
        val taken = mutableSetOf<String>()
        return names.take(Sheets.TABS_MOST).mapIndexed { index, name ->
            val page = if (index == 0 || first.sheets == null) first else when (val read = each(name)) {
                is SheetReader.Reading.Read -> read.page
                is SheetReader.Reading.Refused -> throw SheetImportRefusedException(read.reason)
            }
            val cells = linkedMapOf<String, String>()
            page.rows.forEachIndexed { row, values ->
                values.forEachIndexed { column, value ->
                    if (value.isNotEmpty()) {
                        val address = SheetCellAt(column, page.from - 1 + row).name
                        cells[address] = if (value[0] == '=' || value[0] == '\'') "'$value" else value
                    }
                }
            }
            page.formulas.forEach { (address, formula) -> cells[address] = if (formula.startsWith("=")) formula else "=$formula" }
            val formats = linkedMapOf<String, SheetCellFormat>()
            page.looks.forEach { (address, look) -> formatOf(look).takeUnless { it.isEmpty }?.let { formats[address] = it } }
            SheetTab(name = uniqueTab(name, taken, index), cells = cells, formats = formats).also { taken += it.name.lowercase() }
        }
    }

    /** How a file drew a cell, as far as a sheet here can draw it. */
    private fun formatOf(look: SheetReader.Look): SheetCellFormat =
        SheetFormats.fromExcel(look.code, look.date).copy(
            bold = look.bold,
            italic = look.italic,
            underline = look.underline,
            strike = look.strike,
            color = look.color,
            fill = look.fill,
            align = look.align,
            wrap = look.wrap,
            border = look.border,
            borderColor = look.borderColor,
        )

    /**
     * A tab name a file gave, made into one a sheet takes: the characters Excel
     * refuses dropped, held to its length, and numbered where two would be the same.
     */
    private fun uniqueTab(said: String, taken: Set<String>, index: Int): String {
        val cleaned = said.filterNot { it in Sheets.TAB_NAME_REFUSED }.trim().trim('\'').take(Sheets.TAB_NAME_LENGTH)
            .ifEmpty { "Sheet${index + 1}" }
        if (cleaned.lowercase() !in taken) return cleaned
        var n = 2
        while (true) {
            val suffix = " ($n)"
            val numbered = cleaned.take(Sheets.TAB_NAME_LENGTH - suffix.length) + suffix
            if (numbered.lowercase() !in taken) return numbered
            n++
        }
    }

    /**
     * A workbook built from the tabs, and the formulas POI could not parse, by
     * cell, with the reason. The reasons are kept beside the workbook rather
     * than written into it: a cell that cannot hold its formula holds its text
     * instead, which is what an export writes, and the reason is what the
     * person and the model are told.
     */
    private data class Built(val book: XSSFWorkbook, val refused: Map<Cell, String>) : AutoCloseable {
        override fun close() = book.close()
    }

    private fun built(tabs: List<SheetTab>): Built {
        val book = XSSFWorkbook()
        val refused = mutableMapOf<Cell, String>()
        val styles = Styles(book)
        // Every tab first: a formula may name a tab that comes after its own.
        tabs.forEach { book.createSheet(it.name) }
        tabs.forEachIndexed { index, tab ->
            val sheet = book.getSheetAt(index)
            tab.widths.forEach { (letters, pixels) ->
                SheetCellAt.columnOf(letters)?.let { sheet.setColumnWidth(it, (pixels * WIDTH_UNITS_PER_PIXEL).toInt().coerceAtMost(255 * 256)) }
            }
            tab.cells.forEach { (address, typed) ->
                val at = requireNotNull(SheetCellAt.of(address))
                val cell = (sheet.getRow(at.row) ?: sheet.createRow(at.row)).createCell(at.column)
                put(cell, typed, refused)
            }
            // A formatted cell that holds nothing is still a cell in the file: a coloured header has empty cells in it.
            tab.formats.forEach { (address, format) ->
                val at = requireNotNull(SheetCellAt.of(address))
                val row = sheet.getRow(at.row) ?: sheet.createRow(at.row)
                (row.getCell(at.column) ?: row.createCell(at.column)).cellStyle = styles.of(format)
            }
        }
        return Built(book, refused)
    }

    /**
     * One cell style per distinct format rather than one per cell: Excel holds
     * at most 64,000 styles in a file, and a column of a thousand bold cells is
     * one style used a thousand times.
     */
    private class Styles(private val book: XSSFWorkbook) {
        private val made = mutableMapOf<SheetCellFormat, XSSFCellStyle>()

        fun of(format: SheetCellFormat): XSSFCellStyle = made.getOrPut(format) {
            book.createCellStyle().apply {
                if (format.bold || format.italic || format.underline || format.strike || format.color != null) {
                    setFont(
                        book.createFont().apply {
                            bold = format.bold
                            italic = format.italic
                            if (format.underline) underline = Font.U_SINGLE
                            strikeout = format.strike
                            format.color?.let { setColor(XSSFColor(rgb(it))) }
                        },
                    )
                }
                format.fill?.let {
                    setFillForegroundColor(XSSFColor(rgb(it)))
                    fillPattern = FillPatternType.SOLID_FOREGROUND
                }
                when (format.align) {
                    SheetFormats.ALIGN_LEFT -> alignment = HorizontalAlignment.LEFT
                    SheetFormats.ALIGN_CENTER -> alignment = HorizontalAlignment.CENTER
                    SheetFormats.ALIGN_RIGHT -> alignment = HorizontalAlignment.RIGHT
                }
                wrapText = format.wrap
                // Thin lines, as a person drawing a table in Excel gets; automatic where no colour was chosen.
                val ink = format.borderColor?.let { XSSFColor(rgb(it)) }
                if (SheetFormats.TOP in format.border) {
                    borderTop = BorderStyle.THIN
                    ink?.let { setTopBorderColor(it) }
                }
                if (SheetFormats.RIGHT in format.border) {
                    borderRight = BorderStyle.THIN
                    ink?.let { setRightBorderColor(it) }
                }
                if (SheetFormats.BOTTOM in format.border) {
                    borderBottom = BorderStyle.THIN
                    ink?.let { setBottomBorderColor(it) }
                }
                if (SheetFormats.LEFT in format.border) {
                    borderLeft = BorderStyle.THIN
                    ink?.let { setLeftBorderColor(it) }
                }
                SheetFormats.excelCode(format)?.let { dataFormat = book.createDataFormat().getFormat(it) }
            }
        }

        private fun rgb(hex: String): ByteArray = ByteArray(3) { hex.substring(1 + it * 2, 3 + it * 2).toInt(16).toByte() }
    }

    private fun put(cell: Cell, typed: String, refused: MutableMap<Cell, String>) {
        when {
            typed.length > 1 && typed[0] == '=' -> try {
                cell.setCellFormula(typed.substring(1))
            } catch (unreadable: FormulaParseException) {
                cell.setCellValue(typed)
                refused[cell] = unreadable.message ?: "the formula cannot be read"
            }
            typed[0] == '\'' -> cell.setCellValue(typed.substring(1))
            typed.equals("TRUE", ignoreCase = true) -> cell.setCellValue(true)
            typed.equals("FALSE", ignoreCase = true) -> cell.setCellValue(false)
            NUMBER.matches(typed.trim()) -> cell.setCellValue(typed.trim().toDouble())
            else -> cell.setCellValue(typed)
        }
    }

    private fun valueOf(cell: Cell, evaluator: FormulaEvaluator): Value = when (cell.cellType) {
        CellType.NUMERIC -> number(cell.numericCellValue)
        CellType.BOOLEAN -> bool(cell.booleanCellValue)
        CellType.FORMULA -> try {
            val worked = evaluator.evaluate(cell)
            when (worked?.cellType) {
                CellType.NUMERIC -> number(worked.numberValue)
                CellType.BOOLEAN -> bool(worked.booleanValue)
                CellType.STRING -> Value("s", worked.stringValue, worked.stringValue)
                CellType.ERROR -> error(FormulaError.forInt(worked.errorValue).string)
                // A formula naming only empty cells comes to nothing, which a spreadsheet draws as 0.
                null, CellType.BLANK -> number(0.0)
                else -> Value("s", "", "")
            }
        } catch (missing: org.apache.poi.ss.formula.eval.NotImplementedException) {
            Value("e", "#NAME?", "#NAME?", generateSequence<Throwable>(missing) { it.cause }.last().message ?: "a function here is not one this server has")
        } catch (failed: RuntimeException) {
            Value("e", "#VALUE!", "#VALUE!", failed.message)
        }
        else -> cell.stringCellValue.let { Value("s", it, it) }
    }

    private fun number(value: Double): Value {
        if (value.isNaN() || value.isInfinite()) return error("#NUM!")
        val shown = BigDecimal(value).round(MathContext(SIGNIFICANT)).stripTrailingZeros().toPlainString().let { if (it == "-0") "0" else it }
        // A whole number goes into the JSON as one, so a model reads 12 and not 12.0.
        val whole = value == Math.rint(value) && kotlin.math.abs(value) < 1e15
        return Value("n", if (whole) value.toLong() else value, shown)
    }

    private fun bool(value: Boolean) = Value("b", value, if (value) "TRUE" else "FALSE")

    private fun error(code: String) = Value("e", code, code)

    private companion object {
        /** A number as a person types one: 12, -3.5, .5, 1e6. Not 12% or 1,5: those stay text rather than guessing a locale. */
        val NUMBER = Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$")

        /** As many digits as a spreadsheet shows before rounding, which hides 0.1 + 0.2 being 0.30000000000000004. */
        const val SIGNIFICANT = 15

        /** Excel's column width is in 1/256 of a character, about seven pixels wide in its default font. */
        const val WIDTH_UNITS_PER_PIXEL = 256.0 / 7.0

        /** What a spreadsheet opening a CSV starts a formula with. */
        const val CSV_FORMULA_START = "=+-@\t\r"

        /**
         * Writes a number out by a format code; US English, so a sheet reads the same to every person and model.
         * One per thread: it keeps the codes it has parsed in a map that is not safe to share.
         */
        val FORMATTER: ThreadLocal<DataFormatter> = ThreadLocal.withInitial { DataFormatter(Locale.US) }

        /** The first index Excel leaves to codes a file defines itself, which is what every one of ours is. */
        const val CUSTOM_FORMAT = 164
    }
}
