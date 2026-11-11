package io.mszymanski.orknux.server.embedded

import com.github.miachm.sods.OdsOptionParameters
import com.github.miachm.sods.OfficeCurrency
import com.github.miachm.sods.OfficePercentage
import com.github.miachm.sods.SpreadSheet
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.poi.poifs.filesystem.FileMagic
import org.apache.poi.openxml4j.exceptions.InvalidFormatException
import org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException
import org.apache.poi.openxml4j.exceptions.ODFNotOfficeXmlFileException
import org.apache.poi.openxml4j.opc.OPCPackage
import org.apache.poi.openxml4j.opc.PackageAccess
import org.apache.poi.openxml4j.util.ZipSecureFile
import org.apache.poi.hssf.usermodel.HSSFFont
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.hssf.util.HSSFColor
import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Color
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.ExtendedColor
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.Font
import org.apache.poi.ss.usermodel.FormulaError
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.ss.util.CellReference
import org.apache.poi.util.XMLHelper
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable
import org.apache.poi.xssf.eventusermodel.XSSFReader
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler
import org.apache.poi.xssf.model.StylesTable
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFComment
import org.springframework.stereotype.Component
import org.xml.sax.Attributes
import org.xml.sax.ContentHandler
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.math.BigDecimal
import java.nio.file.Files
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * A page of rows out of a CSV or a workbook - .xlsx, .xls or .ods - for sheet_read.
 *
 * Rows are numbered as the sheet numbers them, from 1, header included, so
 * "row 40" means the same thing to the agent and to the person who opened the
 * file. A page is [Limits.rows] rows from where it was asked to start, cut
 * shorter once its cells come to [Limits.characters]; the whole sheet is still
 * scanned so the answer can say how many rows there are.
 *
 * Every cell is answered as text: a number as its plain value (12.5, not the
 * "12,50 zł" a format would draw), a date as ISO 8601, a formula as the value
 * the file last saved for it, a boolean as TRUE or FALSE and an error as its
 * code. A workbook's formulas are answered as well, by cell ("B5" to
 * "=SUM(B2:B3)"): with the values alone, an agent asked to check a sheet's
 * sums unpacked the .ods in a box to find them. Parsing is the libraries' - Commons CSV, POI, and SODS for an .ods -
 * and nothing here reads a byte of any of those formats by hand.
 */
@Component
class SheetReader {

    data class Limits(
        val rows: Int,
        val characters: Int,
        val unpackedBytes: Long,
        /** How many cells' looks may be kept, shared by every read handed the same one; every look where null. */
        val looks: LookBudget? = null,
    )

    /**
     * Looks still to be kept, counted down across every tab of one import.
     * Without it a file of empty, styled cells - a template's whole grid, or a
     * few bytes per cell made on purpose - held a look per cell in memory, tab
     * after tab, before anything was trimmed to what a sheet keeps.
     */
    class LookBudget(most: Int) {
        private var left = most

        /** One more look kept, where there is room for it. */
        fun take(): Boolean = (left > 0).also { if (it) left-- }
    }

    /** What one read found. [sheets] is null for a CSV, [delimiter] null for a workbook. */
    data class Page(
        val sheets: List<String>?,
        val sheet: String?,
        val delimiter: Char?,
        val header: List<String>?,
        val rows: List<List<String>>,
        /** The page's formulas by cell, "B5" to "=SUM(B2:B3)"; empty for a CSV. */
        val formulas: Map<String, String>,
        val from: Int,
        val to: Int,
        val total: Int,
        /** How the page's cells are drawn, by cell, where they are drawn as anything; read only when asked, and never for a CSV or an .ods. */
        val looks: Map<String, Look> = emptyMap(),
    )

    /**
     * How a cell in a file is drawn, as the file says it: colours as `#rrggbb`,
     * [align] left, center or right, and [code] the Excel number format where
     * it is not General. What the default is drawn as - black words, no fill -
     * is left out, so a cell that was never formatted has no look at all.
     */
    data class Look(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strike: Boolean = false,
        val color: String? = null,
        val fill: String? = null,
        val align: String? = null,
        val wrap: Boolean = false,
        val code: String? = null,
        val date: Boolean = false,
        /** The sides with a line along them, of top, right, bottom and left, in that order. */
        val border: List<String> = emptyList(),
        /** The lines' colour where the file gave one other than black. */
        val borderColor: String? = null,
    ) {
        val isPlain: Boolean get() = this == Look()
    }

    sealed interface Reading {
        data class Read(val page: Page) : Reading
        data class Refused(val reason: String) : Reading
    }

    /* ------------------------------------------------------------------ CSV */

    /**
     * A CSV's rows. The delimiter is [delimiter] where one was given, else the
     * one of comma, semicolon, tab and bar that splits the opening lines most
     * evenly - a Polish Excel writes semicolons, because the comma is its
     * decimal point. A byte-order mark is not part of the first column's name.
     */
    fun csv(text: String, from: Int, to: Int?, limits: Limits, delimiter: Char? = null): Reading {
        val body = text.removePrefix("﻿")
        val chosen = delimiter ?: guessDelimiter(body)
        val page = Window(from, to, limits)
        return try {
            CSVParser.parse(StringReader(body), formatFor(chosen)).use { parser ->
                parser.forEach { record -> page.offer(record.toList().takeUnless { it.size == 1 && it[0].isEmpty() } ?: emptyList()) }
            }
            Reading.Read(page.finish(sheets = null, sheet = null, delimiter = chosen))
        } catch (failure: Exception) {
            val reason = (failure.cause as? java.io.IOException ?: failure).message ?: failure.javaClass.simpleName
            Reading.Refused("That CSV could not be read past row ${page.seen}: $reason")
        }
    }

    private fun formatFor(delimiter: Char): CSVFormat = CSVFormat.RFC4180.builder()
        .setDelimiter(delimiter)
        .setIgnoreEmptyLines(false)
        .setTrailingData(true)
        .setLenientEof(true)
        .get()

    /** The candidate giving the opening records the most columns, most consistently. */
    private fun guessDelimiter(body: String): Char {
        val opening = body.lineSequence().take(GUESS_LINES).joinToString("\n")
        return DELIMITERS.maxByOrNull { candidate ->
            val counts = runCatching {
                CSVParser.parse(StringReader(opening), formatFor(candidate)).use { parser ->
                    parser.map { it.size() }.filter { it > 0 }
                }
            }.getOrNull().orEmpty()
            val first = counts.firstOrNull() ?: 0
            if (first < 2) 0 else counts.count { it == first } * 1000 + first
        }?.takeIf { candidate ->
            body.lineSequence().firstOrNull()?.contains(candidate) == true
        } ?: ','
    }

    /* ------------------------------------------------------------- workbook */

    /**
     * Whether these bytes look like a workbook: an OLE2 file (.xls) or a zip,
     * which an .xlsx and an .ods both are. Which of those a zip is, or whether
     * it is neither, is [workbook]'s to say - with a reason a model can repeat.
     */
    fun isWorkbook(bytes: ByteArray): Boolean = magicOf(bytes) in setOf(FileMagic.OOXML, FileMagic.OLE2)

    /**
     * A workbook's sheet names, and a page of one sheet: [sheet] by name, or
     * the first. An .xlsx is streamed, so only the page is ever held; a part
     * that unpacks past [Limits.unpackedBytes], or inflates suspiciously far,
     * is refused by POI before it is read.
     */
    fun workbook(bytes: ByteArray, sheet: String?, from: Int, to: Int?, limits: Limits, styled: Boolean = false): Reading =
        try {
            when (magicOf(bytes)) {
                FileMagic.OOXML -> when (val odf = openDocumentTypeOf(bytes)) {
                    null -> xlsx(bytes, sheet, from, to, limits, styled)
                    ODS -> ods(bytes, sheet, from, to, limits)
                    else -> Reading.Refused(
                        "That is an OpenDocument file of another kind ($odf), not a spreadsheet. " +
                            "A workbook is an .ods, an .xlsx or an .xls.",
                    )
                }
                FileMagic.OLE2 -> xls(bytes, sheet, from, to, limits, styled)
                else -> Reading.Refused("That is not a workbook: it is neither an .ods, an .xlsx nor an .xls.")
            }
        } catch (unannounced: ODFNotOfficeXmlFileException) {
            /*
             * POI saw an OpenDocument file its mimetype entry did not announce
             * - one written with that entry out of place, which the format
             * forbids and some writers do anyway. SODS reads it or says why not.
             */
            try {
                ods(bytes, sheet, from, to, limits)
            } catch (failure: Exception) {
                Reading.Refused("That workbook could not be read: " + (failure.message ?: failure.javaClass.simpleName))
            }
        } catch (notOffice: NotOfficeXmlFileException) {
            Reading.Refused("That is a zip but not a workbook: it holds neither an .ods nor an .xlsx.")
        } catch (unpackaged: InvalidFormatException) {
            // A zip with no content-types part, which every .xlsx has: an archive of something else.
            Reading.Refused(
                "That is a zip but not a workbook: it holds neither an .ods nor an .xlsx (${unpackaged.message}).",
            )
        } catch (failure: Exception) {
            Reading.Refused("That workbook could not be read: " + (failure.message ?: failure.javaClass.simpleName))
        }

    private fun magicOf(bytes: ByteArray): FileMagic? =
        runCatching { FileMagic.valueOf(ByteArrayInputStream(bytes)) }.getOrNull()

    private fun xlsx(bytes: ByteArray, sheet: String?, from: Int, to: Int?, limits: Limits, styled: Boolean): Reading {
        /*
         * A file rather than a stream: opened from a stream, POI unpacks every
         * part into memory first, which is the size limit and not a streaming
         * read. The limits are POI's own zip-bomb guard and are process-wide;
         * nothing else here opens a workbook.
         */
        ZipSecureFile.setMaxEntrySize(limits.unpackedBytes)
        val file = Files.createTempFile("orknux-sheet-", ".xlsx")
        try {
            Files.write(file, bytes)
            OPCPackage.open(file.toFile(), PackageAccess.READ).use { pkg ->
                val reader = XSSFReader(pkg)
                val strings = ReadOnlySharedStringsTable(pkg, false)
                val styles = reader.stylesTable
                val names = mutableListOf<String>()
                var page: Window? = null
                var read: String? = null
                val sheets = reader.sheetsData as XSSFReader.SheetIterator
                while (sheets.hasNext()) {
                    sheets.next().use { stream ->
                        val name = sheets.sheetName
                        names += name
                        if (page == null && (sheet == null || name.equals(sheet.trim(), ignoreCase = true))) {
                            val window = Window(from, to, limits)
                            val rows = Rows(window, if (styled) XssfLooks(styles) else null)
                            val handler = XSSFSheetXMLHandler(styles, null, strings, rows, CellText(), false)
                            XMLHelper.newXMLReader().apply { contentHandler = FormulaTap(handler, rows) }.parse(InputSource(stream))
                            page = window
                            read = name
                        }
                    }
                }
                val found = page ?: return Reading.Refused(noSheet(sheet, names))
                return Reading.Read(found.finish(sheets = names, sheet = read, delimiter = null))
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    /** The streaming reader's rows, gaps in the sheet's numbering kept as empty rows. */
    private class Rows(private val window: Window, private val looks: XssfLooks?) : XSSFSheetXMLHandler.SheetContentsHandler {
        private var cells = mutableListOf<String>()
        private var formulas = mutableMapOf<Int, String>()
        private var styles = mutableMapOf<Int, Look>()
        private var last = 0

        /** A formula [FormulaTap] read off the cell at [reference], which the streaming handler only answers the value of. */
        fun formula(reference: String, text: String) {
            formulas[CellReference(reference).col.toInt()] = text
        }

        /** The style [FormulaTap] read off the cell at [reference]: an index into the file's styles, kept where it draws anything. */
        fun style(reference: String, index: Int) {
            val look = looks?.of(index) ?: return
            styles[CellReference(reference).col.toInt()] = look
        }

        override fun startRow(rowNum: Int) {
            while (last < rowNum) {
                window.offer(emptyList())
                last++
            }
            cells = mutableListOf()
            formulas = mutableMapOf()
            styles = mutableMapOf()
        }

        override fun endRow(rowNum: Int) {
            window.offer(cells.dropLastWhile { it.isEmpty() }, formulas, styles)
            last = rowNum + 1
        }

        override fun cell(cellReference: String?, formattedValue: String?, comment: XSSFComment?) {
            val column = cellReference?.let { CellReference(it).col.toInt() } ?: cells.size
            while (cells.size < column) cells += ""
            cells += formattedValue.orEmpty()
        }
    }

    /**
     * Passes a sheet's XML on to POI's handler and keeps each cell's `<f>` as
     * it goes by: the handler answers a formula's saved value or its formula,
     * never both, and the value is the cell. A shared formula is answered on
     * the cell that holds its text; the cells that share it hold none.
     */
    private class FormulaTap(private val inner: ContentHandler, private val rows: Rows) : ContentHandler by inner {
        private var cell: String? = null
        private var formula: StringBuilder? = null

        override fun startElement(uri: String?, localName: String?, qName: String?, atts: Attributes?) {
            when (nameOf(localName, qName)) {
                "c" -> {
                    cell = atts?.getValue("r")
                    val style = atts?.getValue("s")?.toIntOrNull()
                    val at = cell
                    if (style != null && style != 0 && at != null) rows.style(at, style)
                }
                "f" -> formula = StringBuilder()
            }
            inner.startElement(uri, localName, qName, atts)
        }

        override fun characters(ch: CharArray?, start: Int, length: Int) {
            if (ch != null) formula?.appendRange(ch, start, start + length)
            inner.characters(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            if (nameOf(localName, qName) == "f") {
                val text = formula?.toString()?.trim().orEmpty()
                val at = cell
                if (text.isNotEmpty() && at != null) rows.formula(at, "=$text")
                formula = null
            }
            inner.endElement(uri, localName, qName)
        }

        private fun nameOf(localName: String?, qName: String?): String =
            localName?.ifEmpty { null } ?: qName.orEmpty().substringAfter(':')
    }

    private fun xls(bytes: ByteArray, sheet: String?, from: Int, to: Int?, limits: Limits, styled: Boolean): Reading =
        WorkbookFactory.create(ByteArrayInputStream(bytes)).use { book ->
            val looked = mutableMapOf<Short, Look?>()
            fun lookOf(style: org.apache.poi.ss.usermodel.CellStyle): Look? = looked.getOrPut(style.index) {
                val font = book.getFontAt(style.fontIndex)
                val ink = (book as? HSSFWorkbook)?.let { (font as? HSSFFont)?.getHSSFColor(it) }
                looks(style, font, hex(ink), if (style.fillPattern == FillPatternType.SOLID_FOREGROUND) hex(style.fillForegroundColorColor) else null)
            }
            val names = (0 until book.numberOfSheets).map(book::getSheetName)
            val name = if (sheet == null) names.firstOrNull() else names.firstOrNull { it.equals(sheet.trim(), ignoreCase = true) }
            name ?: return Reading.Refused(noSheet(sheet, names))
            val window = Window(from, to, limits)
            val formatter = CellText()
            val rows = book.getSheet(name)
            for (number in 0..rows.lastRowNum) {
                val row = rows.getRow(number)
                if (row == null) {
                    window.offer(emptyList())
                    continue
                }
                val formulas = mutableMapOf<Int, String>()
                val styles = mutableMapOf<Int, Look>()
                val cells = (0 until maxOf(row.lastCellNum.toInt(), 0)).map { column ->
                    row.getCell(column)?.let { cell ->
                        if (styled && cell.cellStyle.index.toInt() != 0) lookOf(cell.cellStyle)?.let { styles[column] = it }
                        if (cell.cellType == CellType.FORMULA) formulas[column] = "=" + cell.cellFormula
                        val type = if (cell.cellType == CellType.FORMULA) cell.cachedFormulaResultType else cell.cellType
                        when (type) {
                            CellType.NUMERIC -> formatter.formatRawCellContents(
                                cell.numericCellValue, cell.cellStyle.dataFormat.toInt(), cell.cellStyle.dataFormatString,
                            )
                            CellType.STRING -> cell.richStringCellValue.string
                            CellType.BOOLEAN -> if (cell.booleanCellValue) "TRUE" else "FALSE"
                            CellType.ERROR -> FormulaError.forInt(cell.errorCellValue).string
                            else -> ""
                        }
                    }.orEmpty()
                }
                window.offer(cells.dropLastWhile { it.isEmpty() }, formulas, styles)
            }
            Reading.Read(window.finish(sheets = names, sheet = name, delimiter = null))
        }

    /**
     * What an OpenDocument zip says it is, or null where it says nothing.
     *
     * The format's own answer: an ODF package opens with an entry called
     * `mimetype` holding its type, so an .ods is known by its content and not
     * by a name a browser or a person gave it - an .ods renamed .xlsx is still
     * read, and an .odt is refused as what it is.
     */
    private fun openDocumentTypeOf(bytes: ByteArray): String? = runCatching {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            zip.nextEntry?.takeIf { it.name == "mimetype" } ?: return null
            zip.readNBytes(MIMETYPE_MOST).decodeToString().trim().ifEmpty { null }
        }
    }.getOrNull()

    /**
     * An .ods, by SODS.
     *
     * SODS holds a whole workbook in memory, and an .ods is a zip - so before
     * it is handed over every XML part is unpacked once and counted, and one
     * that comes to more than [Limits.unpackedBytes] refuses the file, as POI
     * refuses an .xlsx part. Counted by inflating rather than read off the
     * sizes the zip declares, which are whatever its writer wanted them to be.
     * Styles, pictures, charts and macros are not loaded: a cell's type is on
     * the cell, and nothing else here is read.
     */
    private fun ods(bytes: ByteArray, sheet: String?, from: Int, to: Int?, limits: Limits): Reading {
        val file = Files.createTempFile("orknux-sheet-", ".ods")
        try {
            Files.write(file, bytes)
            ZipFile(file.toFile()).use { zip ->
                val buffer = ByteArray(64 * 1024)
                for (entry in zip.entries()) {
                    if (!entry.name.endsWith(".xml")) continue
                    var unpacked = 0L
                    zip.getInputStream(entry).use { stream ->
                        while (true) {
                            val read = stream.read(buffer)
                            if (read < 0) break
                            unpacked += read
                            if (unpacked > limits.unpackedBytes) {
                                return Reading.Refused(
                                    "That workbook could not be read: its part ${entry.name} unpacks to more than " +
                                        "${limits.unpackedBytes / (1024 * 1024)} MB, the most one part may.",
                                )
                            }
                        }
                    }
                }
            }
            val options = OdsOptionParameters().apply {
                isLoadStyles = false
                isLoadImages = false
                isLoadGraphs = false
                isLoadMacros = false
            }
            val book = SpreadSheet(file.toFile(), options)
            val names = book.sheets.map { it.name }
            val chosen = if (sheet == null) {
                book.sheets.firstOrNull()
            } else {
                book.sheets.firstOrNull { it.name.equals(sheet.trim(), ignoreCase = true) }
            }
            chosen ?: return Reading.Refused(noSheet(sheet, names))
            val window = Window(from, to, limits)
            val empty = chosen.maxRows == 0 || chosen.maxColumns == 0
            val values: Array<Array<Any?>> = if (empty) emptyArray() else chosen.dataRange.values
            val formulas: Array<Array<String?>> = if (empty) emptyArray() else chosen.dataRange.formulas
            // Trailing empty rows are formatting a sheet carries, not rows of it - a .ods often has thousands.
            values.map { row -> row.map(::odsText).dropLastWhile { it.isEmpty() } }
                .dropLastWhile { it.isEmpty() }
                .forEachIndexed { at, cells ->
                    val held = formulas.getOrNull(at).orEmpty().withIndex()
                        .mapNotNull { (column, said) -> odsFormula(said)?.let { column to it } }
                        .toMap()
                    window.offer(cells, held)
                }
            return Reading.Read(window.finish(sheets = names, sheet = chosen.name, delimiter = null))
        } finally {
            Files.deleteIfExists(file)
        }
    }

    /**
     * An .ods cell as text, by the rules [CellText] keeps for a workbook: a
     * number plain, a date ISO 8601 and alone where it has no time of day, a
     * time of day alone, a boolean TRUE or FALSE. SODS answers each typed, so
     * a currency or a percentage is its number - 0.04, not "4%".
     */
    private fun odsText(value: Any?): String = when (value) {
        null -> ""
        is String -> value
        is Boolean -> if (value) "TRUE" else "FALSE"
        is Number -> plain(value.toDouble())
        is LocalDate -> value.toString()
        is LocalDateTime -> value.truncatedTo(ChronoUnit.SECONDS).let { at ->
            if (at == at.toLocalDate().atStartOfDay()) at.toLocalDate().toString() else at.toString()
        }
        is Duration ->
            if (!value.isNegative && value < Duration.ofDays(1)) {
                LocalTime.MIDNIGHT.plus(value).truncatedTo(ChronoUnit.SECONDS).toString()
            } else {
                value.toString()
            }
        is OfficeCurrency -> value.value?.let(::plain).orEmpty()
        is OfficePercentage -> value.value?.let(::plain).orEmpty()
        else -> value.toString()
    }

    /**
     * An .ods formula as a spreadsheet shows it: OpenFormula writes
     * `of:=SUM([.B2:.B3])`, with a namespace and its references bracketed and
     * dotted, which a person reads as `=SUM(B2:B3)`.
     */
    private fun odsFormula(said: String?): String? {
        val text = said?.trim()?.ifEmpty { null } ?: return null
        val bare = NAMESPACE.replaceFirst(text, "").let { if (it.startsWith("=")) it else "=$it" }
        return REFERENCE.replace(bare) { found ->
            found.groupValues[1].split(':').joinToString(":") { part ->
                val sheet = part.substringBefore('.', "").removePrefix("$")
                val cell = part.substringAfter('.')
                if (sheet.isEmpty()) cell else "$sheet.$cell"
            }
        }
    }

    private fun noSheet(asked: String?, names: List<String>): String =
        if (asked == null || names.isEmpty()) "That workbook has no sheets."
        else "That workbook has no sheet called \"$asked\". Its sheets are: ${names.joinToString(", ")}."

    /**
     * A cell's number as text: a date as ISO 8601 - a date alone where it has
     * no time of day, a time alone where it has no date - and anything else as
     * its plain value, since what the format draws ("12,50 zł", "4%") is
     * the reader's locale and not the number.
     */
    private class CellText : DataFormatter() {
        override fun formatRawCellContents(value: Double, formatIndex: Int, formatString: String?, use1904Windowing: Boolean): String {
            if (DateUtil.isADateFormat(formatIndex, formatString) && DateUtil.isValidExcelDate(value)) {
                val at = DateUtil.getLocalDateTime(value, use1904Windowing, true).truncatedTo(ChronoUnit.SECONDS)
                return when {
                    value < 1 -> at.toLocalTime().toString()
                    at == at.toLocalDate().atStartOfDay() -> at.toLocalDate().toString()
                    else -> at.toString()
                }
            }
            return plain(value)
        }
    }

    /** An .xlsx's styles read as [Look]s, each once however many cells use it. */
    private class XssfLooks(private val styles: StylesTable) {
        private val read = mutableMapOf<Int, Look?>()

        fun of(index: Int): Look? = read.getOrPut(index) {
            runCatching {
                val style = styles.getStyleAt(index) ?: return@runCatching null
                val font = style.font
                looks(style, font, hex(font.xssfColor), if (style.fillPattern == FillPatternType.SOLID_FOREGROUND) hex(style.fillForegroundColorColor) else null)
            }.getOrNull()
        }
    }

    /** Which rows are kept: [from] onward, at most so many, and no more characters than allowed. */
    private class Window(from: Int, to: Int?, private val limits: Limits) {
        private val first = from.coerceAtLeast(1)
        private val last = minOf((to ?: Int.MAX_VALUE).toLong(), first.toLong() + limits.rows - 1)
        private val kept = mutableListOf<List<String>>()
        private val formulas = linkedMapOf<String, String>()
        private val looks = linkedMapOf<String, Look>()
        private var characters = 0
        private var full = false
        private var header: List<String>? = null

        /** How many rows have been offered, which is the row number of the last. */
        var seen = 0
            private set

        /** A row, its formulas by column, and how its cells are drawn by column. */
        fun offer(cells: List<String>, rowFormulas: Map<Int, String> = emptyMap(), rowLooks: Map<Int, Look> = emptyMap()) {
            seen++
            if (seen == 1) header = cells
            if (full || seen < first || seen > last) return
            val named = rowFormulas.toSortedMap().mapKeys { (column, _) -> CellReference.convertNumToColString(column) + seen }
            val size = cells.sumOf { it.length + 3 } + 2 + named.entries.sumOf { it.key.length + it.value.length + 6 }
            if (kept.isNotEmpty() && characters + size > limits.characters) {
                full = true
                return
            }
            kept += cells
            formulas += named
            rowLooks.toSortedMap().forEach { (column, look) ->
                if (limits.looks?.take() != false) looks[CellReference.convertNumToColString(column) + seen] = look
            }
            characters += size
        }

        fun finish(sheets: List<String>?, sheet: String?, delimiter: Char?) = Page(
            sheets = sheets,
            sheet = sheet,
            delimiter = delimiter,
            header = header.takeIf { first > 1 },
            rows = kept,
            formulas = formulas,
            from = first,
            to = first + kept.size - 1,
            total = seen,
            looks = looks,
        )
    }

    private companion object {
        /** What an .ods says it is, in its `mimetype` entry. */
        const val ODS = "application/vnd.oasis.opendocument.spreadsheet"

        /** Longer than any OpenDocument type; a `mimetype` entry is never read past it. */
        const val MIMETYPE_MOST = 200

        /** A style and its font as a [Look]; null where it draws a cell the way an unformatted one is drawn. */
        fun looks(style: CellStyle, font: Font, ink: String?, fill: String?): Look? {
            val code = style.dataFormatString?.takeUnless { it.isBlank() || it.equals("General", ignoreCase = true) }
            val look = Look(
                bold = font.bold,
                italic = font.italic,
                underline = font.underline != Font.U_NONE,
                strike = font.strikeout,
                color = ink?.takeUnless { it == "#000000" },
                fill = fill?.takeUnless { it == "#ffffff" },
                align = when (style.alignment) {
                    HorizontalAlignment.LEFT -> "left"
                    HorizontalAlignment.CENTER, HorizontalAlignment.CENTER_SELECTION -> "center"
                    HorizontalAlignment.RIGHT -> "right"
                    else -> null
                },
                wrap = style.wrapText,
                border = listOfNotNull(
                    "top".takeIf { style.borderTop != BorderStyle.NONE },
                    "right".takeIf { style.borderRight != BorderStyle.NONE },
                    "bottom".takeIf { style.borderBottom != BorderStyle.NONE },
                    "left".takeIf { style.borderLeft != BorderStyle.NONE },
                ),
                borderColor = (style as? XSSFCellStyle)?.let { held ->
                    listOf(held.topBorderXSSFColor, held.rightBorderXSSFColor, held.bottomBorderXSSFColor, held.leftBorderXSSFColor)
                        .firstNotNullOfOrNull { hex(it) }
                }?.takeUnless { it == "#000000" },
                code = code,
                date = code != null && DateUtil.isADateFormat(style.dataFormat.toInt(), code),
            )
            return look.takeUnless { it.isPlain }
        }

        /** A colour as `#rrggbb`; null for "automatic", which is the reader's ordinary ink. */
        fun hex(color: Color?): String? {
            val bytes = when (color) {
                is ExtendedColor -> if (color.isAuto) null else (color.rgbWithTint ?: color.rgb)
                is HSSFColor -> if (color.index == HSSFColor.HSSFColorPredefined.AUTOMATIC.index) null
                    else color.triplet.map { it.toByte() }.toByteArray()
                else -> null
            } ?: return null
            if (bytes.size < 3) return null
            return "#" + bytes.takeLast(3).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        /** A number as its plain value: 12.5, 3, 0.04 - never 3.0 or 1.2E7. */
        fun plain(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

        val DELIMITERS = listOf(',', ';', '\t', '|')

        /** An OpenFormula reference, `[.B2]`, `[.B2:.B3]` or `[$Sheet2.A1]`. */
        val REFERENCE = Regex("\\[([^\\]]+)]")

        /** The namespace an .ods may write before a formula, `of:` - and only before it: a range has a colon too. */
        val NAMESPACE = Regex("^[a-z]+:(?==)")

        /** How many opening lines decide the delimiter: enough to see a pattern, few enough to be instant. */
        const val GUESS_LINES = 20
    }
}
