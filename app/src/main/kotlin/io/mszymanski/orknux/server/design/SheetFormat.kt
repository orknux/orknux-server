package io.mszymanski.orknux.server.design

import io.mszymanski.orknux.server.graphql.Refusal
import tools.jackson.databind.JsonNode

/**
 * How one cell is drawn: its type, its colours, where its text sits and how a
 * number in it is written out.
 *
 * Kept beside what was typed rather than in it, in the tab's `formats` map, so
 * a workbook written before there was any formatting reads exactly as it did,
 * and formatting a cell never changes what a formula reading it comes to. A
 * cell may be formatted and empty - a coloured header row has empty cells in
 * it - so the two maps are separate rather than one map of cells with a style.
 *
 * Colours are the sheet's own, `#rrggbb`, the same in either theme and in every
 * export, so they are not the interface's tokens. A number format is one of a
 * few a person picks from a toolbar, not an Excel format code: a model writes
 * one of five words reliably and an Excel code unreliably, and each of them is
 * written out as a code where a code is needed ([SheetFormats.excelCode]).
 */
data class SheetCellFormat(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val color: String? = null,
    val fill: String? = null,
    /** left, center or right; null is a spreadsheet's own: text to the left, numbers to the right. */
    val align: String? = null,
    val wrap: Boolean = false,
    /** number, percent, currency or date; null is General, written as it comes. */
    val number: String? = null,
    /** Places after the point, for number, percent and currency; null is the format's usual. */
    val decimals: Int? = null,
    /** The currency's symbol, for currency; null is [SheetFormats.CURRENCY_USUAL]. */
    val currency: String? = null,
    /**
     * The sides a line is drawn along, of top, right, bottom and left, in that
     * order; empty for none. Per cell rather than per range, as Excel keeps
     * them, so a range's outline is its edge cells' outer sides.
     */
    val border: List<String> = emptyList(),
    /** The lines' colour; null is the sheet's ordinary ink, black in a file. */
    val borderColor: String? = null,
    /**
     * Written with no comma between thousands, for number and currency: 2024
     * rather than 2,024, as a year or an id is. False, the toolbar's, keeps the
     * comma; true comes from a file whose code had none, such as `0.00`.
     */
    val ungrouped: Boolean = false,
) {
    val isEmpty: Boolean get() = this == NONE

    /** As the workbook stores it and the tools answer it: only what is set. */
    fun asMap(): Map<String, Any> = buildMap {
        if (bold) put("bold", true)
        if (italic) put("italic", true)
        if (underline) put("underline", true)
        if (strike) put("strike", true)
        color?.let { put("color", it) }
        fill?.let { put("fill", it) }
        align?.let { put("align", it) }
        if (wrap) put("wrap", true)
        number?.let { put("number", it) }
        decimals?.let { put("decimals", it) }
        currency?.let { put("currency", it) }
        if (border.isNotEmpty()) put("border", border)
        borderColor?.let { put("borderColor", it) }
        if (ungrouped) put("ungrouped", true)
    }

    companion object {
        val NONE = SheetCellFormat()
    }
}

/** Reads, checks and writes out a cell's [SheetCellFormat]. */
object SheetFormats {

    const val ALIGN_LEFT = "left"
    const val ALIGN_CENTER = "center"
    const val ALIGN_RIGHT = "right"
    val ALIGNS = listOf(ALIGN_LEFT, ALIGN_CENTER, ALIGN_RIGHT)

    const val NUMBER = "number"
    const val PERCENT = "percent"
    const val CURRENCY = "currency"
    const val DATE = "date"
    val NUMBERS = listOf(NUMBER, PERCENT, CURRENCY, DATE)

    /** Excel's own most. */
    const val DECIMALS_MOST = 30

    /** A symbol, not a sentence: zł, CHF, US$. */
    const val CURRENCY_LENGTH = 5

    /** What a currency format is written with when it names none. */
    const val CURRENCY_USUAL = "$"

    /** The symbols written before the amount; every other one, zł and kr among them, is written after it. */
    private val CURRENCY_BEFORE = setOf("$", "£", "€", "¥", "US$", "A$", "C$")

    /** How a date is written: the one order nobody reads two ways. */
    const val DATE_CODE = "yyyy-mm-dd"

    const val TOP = "top"
    const val RIGHT = "right"
    const val BOTTOM = "bottom"
    const val LEFT = "left"
    /** A cell's sides, in the order a border is kept in. */
    val SIDES = listOf(TOP, RIGHT, BOTTOM, LEFT)

    val KEYS = listOf("bold", "italic", "underline", "strike", "color", "fill", "align", "wrap", "number", "decimals", "currency", "border", "borderColor", "ungrouped")

    private val HEX = Regex("^#?([0-9a-fA-F]{6}|[0-9a-fA-F]{3})$")

    /**
     * A format as it is sent - by the editor, in a workbook, or by a model -
     * checked and said back in the words of [where]. Unknown keys are refused
     * rather than dropped: a model that wrote `colour` is told, rather than
     * left believing the cell is red.
     */
    fun read(node: JsonNode, where: String): SheetCellFormat {
        if (!node.isObject) throw SheetWorkbookInvalidException("the formatting of $where has to be an object, such as {\"bold\": true}")
        return patch(SheetCellFormat.NONE, node, where)
    }

    /**
     * [over] with what [node] sets changed: a key given a value sets it, a key
     * given null or false takes it away, and a key left out is kept - so a
     * model can make a range bold without knowing what colour it already was.
     */
    fun patch(over: SheetCellFormat, node: JsonNode, where: String): SheetCellFormat {
        if (!node.isObject) throw SheetWorkbookInvalidException("the formatting of $where has to be an object, such as {\"bold\": true}")
        var made = over
        node.properties().forEach { (key, value) ->
            val gone = value.isNull || (value.isBoolean && !value.booleanValue())
            made = when (key) {
                "bold" -> made.copy(bold = flag(value, key, where))
                "italic" -> made.copy(italic = flag(value, key, where))
                "underline" -> made.copy(underline = flag(value, key, where))
                "strike" -> made.copy(strike = flag(value, key, where))
                "wrap" -> made.copy(wrap = flag(value, key, where))
                "ungrouped" -> made.copy(ungrouped = flag(value, key, where))
                "color" -> made.copy(color = if (gone) null else colour(value, key, where))
                "fill" -> made.copy(fill = if (gone) null else colour(value, key, where))
                "align" -> made.copy(align = if (gone) null else oneOf(value, ALIGNS, key, where))
                "number" -> made.copy(number = if (gone) null else oneOf(value, NUMBERS + "general", key, where).takeIf { it != "general" })
                "decimals" -> made.copy(decimals = if (gone) null else decimals(value, where))
                "currency" -> made.copy(currency = if (gone) null else currency(value, where))
                "border" -> made.copy(border = if (gone) emptyList() else sides(value, where))
                "borderColor" -> made.copy(borderColor = if (gone) null else colour(value, key, where))
                else -> throw SheetWorkbookInvalidException(
                    "$where has no formatting called $key; a cell's formatting is ${KEYS.joinToString(", ")}",
                )
            }
        }
        return made
    }

    private fun flag(value: JsonNode, key: String, where: String): Boolean = when {
        value.isNull -> false
        value.isBoolean -> value.booleanValue()
        else -> throw SheetWorkbookInvalidException("$key in the formatting of $where is true or false")
    }

    private fun colour(value: JsonNode, key: String, where: String): String {
        val said = if (value.isString) value.stringValue().trim() else ""
        val match = HEX.matchEntire(said)
            ?: throw SheetWorkbookInvalidException("$key in the formatting of $where is a colour such as #c00000")
        val digits = match.groupValues[1].lowercase()
        return "#" + if (digits.length == 3) digits.map { "$it$it" }.joinToString("") else digits
    }

    private fun oneOf(value: JsonNode, allowed: List<String>, key: String, where: String): String {
        val said = if (value.isString) value.stringValue().trim().lowercase() else ""
        if (said !in allowed) throw SheetWorkbookInvalidException("$key in the formatting of $where is one of ${allowed.joinToString(", ")}")
        return said
    }

    private fun decimals(value: JsonNode, where: String): Int {
        if (!value.isNumber || value.asDouble() != Math.rint(value.asDouble()) || value.asInt() !in 0..DECIMALS_MOST) {
            throw SheetWorkbookInvalidException("decimals in the formatting of $where is a whole number from 0 to $DECIMALS_MOST")
        }
        return value.asInt()
    }

    /**
     * The sides a border is drawn along: a list of them, one side's name, or
     * all and none. Kept in [SIDES]' order whatever order they were said in,
     * so two cells bordered alike are formatted alike.
     */
    private fun sides(value: JsonNode, where: String): List<String> {
        val said: List<String> = when {
            value.isArray -> (0 until value.size()).map { at -> value.get(at).let { if (it.isString) it.stringValue().trim().lowercase() else "" } }
            value.isString -> when (val one = value.stringValue().trim().lowercase()) {
                "all" -> SIDES
                "none", "" -> emptyList()
                else -> listOf(one)
            }
            else -> listOf("")
        }
        if (said.any { it !in SIDES }) {
            throw SheetWorkbookInvalidException(
                "border in the formatting of $where is all, none, or a list of the sides to draw: ${SIDES.joinToString(", ")}",
            )
        }
        return SIDES.filter { it in said }
    }

    private fun currency(value: JsonNode, where: String): String {
        val said = if (value.isString) value.stringValue().trim() else ""
        if (said.isEmpty() || said.length > CURRENCY_LENGTH || said.any { it == '"' || it.isISOControl() }) {
            throw SheetWorkbookInvalidException("currency in the formatting of $where is a symbol of at most $CURRENCY_LENGTH characters, such as zł")
        }
        return said
    }

    /** The places a number format is written with when it names none: two for money and numbers, none for a percentage. */
    fun decimalsOf(format: SheetCellFormat): Int = format.decimals ?: if (format.number == PERCENT) 0 else 2

    /** The Excel format code the cell is written out with; null for General. */
    fun excelCode(format: SheetCellFormat): String? {
        val places = decimalsOf(format).let { if (it == 0) "" else "." + "0".repeat(it) }
        val whole = if (format.ungrouped) "0" else "#,##0"
        return when (format.number) {
            NUMBER -> "$whole$places"
            PERCENT -> "0$places%"
            CURRENCY -> {
                val symbol = format.currency ?: CURRENCY_USUAL
                if (symbol in CURRENCY_BEFORE) "\"$symbol\"$whole$places" else "$whole$places \"$symbol\""
            }
            DATE -> DATE_CODE
            else -> null
        }
    }

    /**
     * An Excel format code read back as one of ours, for a file brought in:
     * the nearest of the five, or General where it is none of them. What is
     * lost - a red negative, a custom date order - is lost on purpose: a
     * format the toolbar cannot show is one nobody here could change.
     */
    fun fromExcel(code: String?, isDate: Boolean): SheetCellFormat {
        val said = code?.trim().orEmpty()
        if (said.isEmpty() || said.equals("General", ignoreCase = true) || said == "@") return SheetCellFormat.NONE
        if (isDate) return SheetCellFormat(number = DATE)
        val first = said.substringBefore(';')
        val bare = first.replace(Regex("\"[^\"]*\"|\\[[^\\]]*\\]|\\\\.|_.|\\*."), "")
        if (!bare.contains('0') && !bare.contains('#')) return SheetCellFormat.NONE
        /*
         * What the five cannot write is left General rather than bent into one
         * of them: a fraction (# ?/?), a scientific number (0.00E+00), a
         * number scaled by a thousand (0,) or padded with zeros (00000) would
         * each be shown as something else, and General at least shows the
         * number it is.
         */
        val digits = bare.substringBefore('%').filter { it in "0#?,./Ee" }
        val integer = digits.substringBefore('.')
        if (digits.any { it in "?/Ee" } || integer.endsWith(',') || integer.count { it == '0' } > 1) return SheetCellFormat.NONE
        val ungrouped = ',' !in integer
        val places = Regex("\\.([0#]+)").find(bare)?.groupValues?.get(1)?.length ?: 0
        val quoted = Regex("\"([^\"]+)\"").find(first)?.groupValues?.get(1)?.trim()
        val bracketed = Regex("\\[\\$([^\\]-]+)").find(first)?.groupValues?.get(1)?.trim()
        val symbol = (quoted ?: bracketed ?: first.firstOrNull { it in "$£€¥" }?.toString())
            ?.takeIf { it.isNotEmpty() && it.length <= CURRENCY_LENGTH && '"' !in it }
        return when {
            bare.contains('%') -> SheetCellFormat(number = PERCENT, decimals = places.takeIf { it != 0 })
            symbol != null -> SheetCellFormat(
                number = CURRENCY,
                decimals = places.takeIf { it != 2 },
                currency = symbol.takeIf { it != CURRENCY_USUAL },
                ungrouped = ungrouped,
            )
            else -> SheetCellFormat(number = NUMBER, decimals = places.takeIf { it != 2 }, ungrouped = ungrouped)
        }
    }
}

/** A sheet past how many cells may be formatted; the same Tunable as the cells, since each costs as much to keep. */
class SheetFormatsTooManyException(val count: Int, val most: Int) :
    RuntimeException("A sheet holds formatting for at most $most cells, and this one would hold $count"), Refusal {
    override val arguments get() = mapOf("count" to count, "most" to most)
}
