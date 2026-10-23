package io.mszymanski.orknux.server.embedded

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.poi.poifs.filesystem.FileMagic
import org.apache.poi.openxml4j.opc.OPCPackage
import org.apache.poi.openxml4j.opc.PackageAccess
import org.apache.poi.openxml4j.util.ZipSecureFile
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.FormulaError
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.ss.util.CellReference
import org.apache.poi.util.XMLHelper
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable
import org.apache.poi.xssf.eventusermodel.XSSFReader
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler
import org.apache.poi.xssf.usermodel.XSSFComment
import org.springframework.stereotype.Component
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.math.BigDecimal
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * A page of rows out of a CSV or a workbook, for sheet_read.
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
 * code. Parsing is the libraries' - Commons CSV and POI - and nothing here
 * reads a byte of either format by hand.
 */
@Component
class SheetReader {

    data class Limits(val rows: Int, val characters: Int, val unpackedBytes: Long)

    /** What one read found. [sheets] is null for a CSV, [delimiter] null for a workbook. */
    data class Page(
        val sheets: List<String>?,
        val sheet: String?,
        val delimiter: Char?,
        val header: List<String>?,
        val rows: List<List<String>>,
        val from: Int,
        val to: Int,
        val total: Int,
    )

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

    /** Whether these bytes are a workbook POI can open, .xlsx or .xls. */
    fun isWorkbook(bytes: ByteArray): Boolean = magicOf(bytes) in setOf(FileMagic.OOXML, FileMagic.OLE2)

    /**
     * A workbook's sheet names, and a page of one sheet: [sheet] by name, or
     * the first. An .xlsx is streamed, so only the page is ever held; a part
     * that unpacks past [Limits.unpackedBytes], or inflates suspiciously far,
     * is refused by POI before it is read.
     */
    fun workbook(bytes: ByteArray, sheet: String?, from: Int, to: Int?, limits: Limits): Reading =
        try {
            when (magicOf(bytes)) {
                FileMagic.OOXML -> xlsx(bytes, sheet, from, to, limits)
                FileMagic.OLE2 -> xls(bytes, sheet, from, to, limits)
                else -> Reading.Refused("That is not a workbook: it is neither an .xlsx nor an .xls.")
            }
        } catch (failure: Exception) {
            Reading.Refused("That workbook could not be read: " + (failure.message ?: failure.javaClass.simpleName))
        }

    private fun magicOf(bytes: ByteArray): FileMagic? =
        runCatching { FileMagic.valueOf(ByteArrayInputStream(bytes)) }.getOrNull()

    private fun xlsx(bytes: ByteArray, sheet: String?, from: Int, to: Int?, limits: Limits): Reading {
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
                            val rows = Rows(window)
                            val handler = XSSFSheetXMLHandler(styles, null, strings, rows, CellText(), false)
                            XMLHelper.newXMLReader().apply { contentHandler = handler }.parse(InputSource(stream))
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
    private class Rows(private val window: Window) : XSSFSheetXMLHandler.SheetContentsHandler {
        private var cells = mutableListOf<String>()
        private var last = 0

        override fun startRow(rowNum: Int) {
            while (last < rowNum) {
                window.offer(emptyList())
                last++
            }
            cells = mutableListOf()
        }

        override fun endRow(rowNum: Int) {
            window.offer(cells.dropLastWhile { it.isEmpty() })
            last = rowNum + 1
        }

        override fun cell(cellReference: String?, formattedValue: String?, comment: XSSFComment?) {
            val column = cellReference?.let { CellReference(it).col.toInt() } ?: cells.size
            while (cells.size < column) cells += ""
            cells += formattedValue.orEmpty()
        }
    }

    private fun xls(bytes: ByteArray, sheet: String?, from: Int, to: Int?, limits: Limits): Reading =
        WorkbookFactory.create(ByteArrayInputStream(bytes)).use { book ->
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
                val cells = (0 until maxOf(row.lastCellNum.toInt(), 0)).map { column ->
                    row.getCell(column)?.let { cell ->
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
                window.offer(cells.dropLastWhile { it.isEmpty() })
            }
            Reading.Read(window.finish(sheets = names, sheet = name, delimiter = null))
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
            return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
        }
    }

    /** Which rows are kept: [from] onward, at most so many, and no more characters than allowed. */
    private class Window(from: Int, to: Int?, private val limits: Limits) {
        private val first = from.coerceAtLeast(1)
        private val last = minOf((to ?: Int.MAX_VALUE).toLong(), first.toLong() + limits.rows - 1)
        private val kept = mutableListOf<List<String>>()
        private var characters = 0
        private var full = false
        private var header: List<String>? = null

        /** How many rows have been offered, which is the row number of the last. */
        var seen = 0
            private set

        fun offer(cells: List<String>) {
            seen++
            if (seen == 1) header = cells
            if (full || seen < first || seen > last) return
            val size = cells.sumOf { it.length + 3 } + 2
            if (kept.isNotEmpty() && characters + size > limits.characters) {
                full = true
                return
            }
            kept += cells
            characters += size
        }

        fun finish(sheets: List<String>?, sheet: String?, delimiter: Char?) = Page(
            sheets = sheets,
            sheet = sheet,
            delimiter = delimiter,
            header = header.takeIf { first > 1 },
            rows = kept,
            from = first,
            to = first + kept.size - 1,
            total = seen,
        )
    }

    private companion object {
        val DELIMITERS = listOf(',', ';', '\t', '|')

        /** How many opening lines decide the delimiter: enough to see a pattern, few enough to be instant. */
        const val GUESS_LINES = 20
    }
}
