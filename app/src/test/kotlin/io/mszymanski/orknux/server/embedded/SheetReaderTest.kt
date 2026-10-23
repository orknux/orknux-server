package io.mszymanski.orknux.server.embedded

import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * sheet_read's parsing: a CSV as Excel and people write one, and a workbook
 * built here with POI - two sheets, a formula and a date - read back as text.
 */
class SheetReaderTest {

    private val reader = SheetReader()
    private val roomy = SheetReader.Limits(rows = 200, characters = 60_000, unpackedBytes = 100L * 1024 * 1024)

    private fun read(reading: SheetReader.Reading): SheetReader.Page {
        assertThat(reading).isInstanceOf(SheetReader.Reading.Read::class.java)
        return (reading as SheetReader.Reading.Read).page
    }

    /* ------------------------------------------------------------------ CSV */

    @Test
    fun `a Polish Excel csv - semicolons, a byte-order mark, quoted fields - is split as it was meant`() {
        val csv = "﻿nazwa;kwota;uwagi\r\n" +
            "\"Kowalski; Jan\";12,50;\"said \"\"hi\"\"\"\r\n" +
            "Nowak;7;\"two\nlines\"\r\n"
        val page = read(reader.csv(csv, 1, null, roomy))
        assertThat(page.delimiter).isEqualTo(';')
        assertThat(page.rows).containsExactly(
            listOf("nazwa", "kwota", "uwagi"),
            listOf("Kowalski; Jan", "12,50", "said \"hi\""),
            listOf("Nowak", "7", "two\nlines"),
        )
        assertThat(page.total).isEqualTo(3)
        assertThat(page.header).isNull()
    }

    @Test
    fun `a comma csv with quoted commas is split on commas`() {
        val page = read(reader.csv("city,note\nWarsaw,\"big, old\"\nGdańsk,sea\n", 1, null, roomy))
        assertThat(page.delimiter).isEqualTo(',')
        assertThat(page.rows[1]).containsExactly("Warsaw", "big, old")
    }

    @Test
    fun `a delimiter given is used over the one found`() {
        val page = read(reader.csv("a,b;c\n1,2;3\n", 1, null, roomy, delimiter = ';'))
        assertThat(page.rows).containsExactly(listOf("a,b", "c"), listOf("1,2", "3"))
    }

    @Test
    fun `a large csv is read a page at a time, the header repeated past the first`() {
        val csv = "id;name\n" + (1..1000).joinToString("") { "$it;item $it\n" }
        val limits = roomy.copy(rows = 50)

        val first = read(reader.csv(csv, 1, null, limits))
        assertThat(first.rows).hasSize(50)
        assertThat(first.from).isEqualTo(1)
        assertThat(first.to).isEqualTo(50)
        assertThat(first.total).isEqualTo(1001)

        val later = read(reader.csv(csv, 501, null, limits))
        assertThat(later.header).containsExactly("id", "name")
        assertThat(later.rows.first()).containsExactly("500", "item 500")
        assertThat(later.to).isEqualTo(550)

        val asked = read(reader.csv(csv, 10, 12, limits))
        assertThat(asked.rows.map { it[0] }).containsExactly("9", "10", "11")

        // And a page stops early once its cells come to the characters allowed - but always holds a row.
        val tight = read(reader.csv(csv, 1, null, roomy.copy(characters = 100)))
        assertThat(tight.rows.size).isBetween(1, 10)
        assertThat(tight.to).isEqualTo(tight.rows.size)
        assertThat(tight.total).isEqualTo(1001)
    }

    @Test
    fun `a csv that breaks its own quoting is read as Excel would read it, not refused`() {
        // Text after a closing quote, and a quote left open at the end - both of which Excel opens.
        val page = read(reader.csv("a,b,c\n1,\"open\"x,2\n3,\"never closed\n", 1, null, roomy))
        assertThat(page.rows[1]).containsExactly("1", "openx", "2")
        assertThat(page.rows[2]).containsExactly("3", "never closed\n")
    }

    /* ------------------------------------------------------------- workbook */

    private fun book(workbook: Workbook): ByteArray = workbook.use { book ->
        val helper = book.creationHelper
        val dated = book.createCellStyle().apply { dataFormat = helper.createDataFormat().getFormat("yyyy-mm-dd") }
        val stamped = book.createCellStyle().apply { dataFormat = helper.createDataFormat().getFormat("dd.mm.yyyy hh:mm") }
        val costs = book.createSheet("Costs")
        costs.createRow(0).apply {
            createCell(0).setCellValue("item")
            createCell(1).setCellValue("amount")
            createCell(2).setCellValue("due")
        }
        costs.createRow(1).apply {
            createCell(0).setCellValue("rent")
            createCell(1).setCellValue(1200.5)
            createCell(2).apply { setCellValue(LocalDate.of(2026, 11, 1)); cellStyle = dated }
        }
        costs.createRow(2).apply {
            createCell(0).setCellValue("power")
            createCell(1).setCellValue(99.5)
            createCell(2).apply { setCellValue(LocalDateTime.of(2026, 11, 3, 14, 30)); cellStyle = stamped }
        }
        // Row 4 left out on purpose: a gap the sheet's numbering keeps.
        costs.createRow(4).apply {
            createCell(0).setCellValue("total")
            createCell(1).cellFormula = "SUM(B2:B3)"
            createCell(3).setCellValue(true)
        }
        book.createSheet("Notes").createRow(0).createCell(0).setCellValue("paid by transfer")
        // The value a formula is answered with is the one the file saved, as Excel saves one.
        helper.createFormulaEvaluator().evaluateAll()
        ByteArrayOutputStream().also(book::write).toByteArray()
    }

    @Test
    fun `an xlsx lists its sheets and answers the first, dates as ISO and formulas as their values`() {
        val page = read(reader.workbook(book(XSSFWorkbook()), null, 1, null, roomy))
        assertThat(page.sheets).containsExactly("Costs", "Notes")
        assertThat(page.sheet).isEqualTo("Costs")
        assertThat(page.rows).containsExactly(
            listOf("item", "amount", "due"),
            listOf("rent", "1200.5", "2026-11-01"),
            listOf("power", "99.5", "2026-11-03T14:30"),
            emptyList(),
            listOf("total", "1300", "", "TRUE"),
        )
        assertThat(page.total).isEqualTo(5)
    }

    @Test
    fun `a sheet is read by name, and one that is not there is refused with the names`() {
        val bytes = book(XSSFWorkbook())
        assertThat(read(reader.workbook(bytes, "notes", 1, null, roomy)).rows).containsExactly(listOf("paid by transfer"))

        val missing = reader.workbook(bytes, "Budget", 1, null, roomy)
        assertThat((missing as SheetReader.Reading.Refused).reason).contains("Budget").contains("Costs, Notes")
    }

    @Test
    fun `an xlsx is paged like a csv`() {
        val page = read(reader.workbook(book(XSSFWorkbook()), null, 2, null, roomy.copy(rows = 2)))
        assertThat(page.header).containsExactly("item", "amount", "due")
        assertThat(page.rows.map { it.first() }).containsExactly("rent", "power")
        assertThat(page.from).isEqualTo(2)
        assertThat(page.to).isEqualTo(3)
        assertThat(page.total).isEqualTo(5)
    }

    @Test
    fun `an xls is read the same way`() {
        val page = read(reader.workbook(book(HSSFWorkbook()), null, 1, null, roomy))
        assertThat(page.sheets).containsExactly("Costs", "Notes")
        assertThat(page.rows[1]).containsExactly("rent", "1200.5", "2026-11-01")
        assertThat(page.rows[4]).containsExactly("total", "1300", "", "TRUE")
    }

    @Test
    fun `a workbook part that unpacks past the limit is refused, not read`() {
        val bytes = XSSFWorkbook().use { book ->
            val sheet = book.createSheet("Big")
            repeat(2000) { sheet.createRow(it).createCell(0).setCellValue("row $it of something long enough") }
            ByteArrayOutputStream().also(book::write).toByteArray()
        }
        val reading = reader.workbook(bytes, null, 1, null, roomy.copy(unpackedBytes = 10_000))
        assertThat(reading).isInstanceOf(SheetReader.Reading.Refused::class.java)
        assertThat(isWorkbook(bytes)).isTrue()
    }

    @Test
    fun `bytes that are no workbook are refused`() {
        assertThat(reader.isWorkbook("a,b\n".toByteArray())).isFalse()
        assertThat(reader.workbook("a,b\n".toByteArray(), null, 1, null, roomy)).isInstanceOf(SheetReader.Reading.Refused::class.java)
    }

    private fun isWorkbook(bytes: ByteArray) = reader.isWorkbook(bytes)
}
